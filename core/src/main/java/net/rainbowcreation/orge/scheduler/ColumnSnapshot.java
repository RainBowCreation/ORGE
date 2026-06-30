package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Range in &rarr; {@link ThermalWorld.ColumnBatch} out for the live {@link MinecraftThermalWorld}
 * (DESIGN 2026-06-01 §5–§6). Builds the player-sphere (+ forced-chunk) union across all loaded levels,
 * projects it to the awake column set + a loaded apron ring, assembles each surviving column into a
 * full-height {@link net.rainbowcreation.orge.engine.ColumnTask} via {@link ColumnAssembler} (reading
 * live blocks + the §5 {@link SectionStore}), then drains this cycle's placement intents into the
 * batch's injection list (spec B3). Publishes the assembled material LUT through {@code lutSink} so the
 * write-back can resolve the engine output species against the same table.
 *
 * <p>Server-thread-confined; reads the live {@link MinecraftServer} lazily through {@code serverSource}.
 * Returns an empty batch when the server is unbound or there is nothing in range.</p>
 */
final class ColumnSnapshot {

    private final SectionStoreManager stores;
    private final CellMaterialTracker cellMaterials;
    private final ActiveSet activeSet;
    private final PendingInjections pendingInjections;
    private final Supplier<MinecraftServer> serverSource;
    private final Consumer<List<Material>> lutSink;

    ColumnSnapshot(SectionStoreManager stores, CellMaterialTracker cellMaterials, ActiveSet activeSet,
                   PendingInjections pendingInjections, Supplier<MinecraftServer> serverSource,
                   Consumer<List<Material>> lutSink) {
        this.stores = stores;
        this.cellMaterials = cellMaterials;
        this.activeSet = activeSet;
        this.pendingInjections = pendingInjections;
        this.serverSource = serverSource;
        this.lutSink = lutSink;
    }

    // Server thread only.
    ThermalWorld.ColumnBatch snapshotColumns(int range) {
        MinecraftServer srv = serverSource.get();
        if (srv == null) {
            return new ThermalWorld.ColumnBatch(List.of(), List.of(MaterialLut.VACUUM));
        }
        ActiveMaterials.State mats = ActiveMaterials.current();
        MaterialLut lut = new MaterialLut(mats.orderedMaterials(), mats.materialSlots());
        List<ThermalWorld.ColumnEntry> entries = new ArrayList<>();

        for (ServerLevel level : srv.getAllLevels()) {
            Identifier dim = level.dimension().identifier();

            Set<SubchunkKey> anchors = new HashSet<>();
            for (ServerPlayer p : level.players()) {
                anchors.add(new SubchunkKey(
                        SectionPos.blockToSectionCoord(p.getBlockX()),
                        SectionPos.blockToSectionCoord(p.getBlockY()),
                        SectionPos.blockToSectionCoord(p.getBlockZ())));
            }
            if (anchors.isEmpty() && level.getForceLoadedChunks().isEmpty()) {
                continue;
            }
            Set<SubchunkKey> union = SphereUnion.expand(anchors, range);
            addForcedSections(level, union);

            // Project the active section set to the awake COLUMN set (any active section ⇒ its column
            // is awake), preserving the §10 dormancy gate (a fully-asleep section contributes nothing,
            // a calm column drops out). activeWithin records new-in-range keys, same as the section path.
            List<SubchunkKey> active = activeSet.activeWithin(dim, new ArrayList<>(union));
            LinkedHashSet<Long> awakeColumns = new LinkedHashSet<>();
            for (SubchunkKey key : active) {
                awakeColumns.add(packColumn(key.cx(), key.cz()));
            }
            // Apron (DESIGN §5/decision 7): expand by one ring of LOADED neighbour columns so a
            // loaded-but-dormant neighbour is stepped as a real column (not misread as an absent-column
            // wall). A genuinely MC-unloaded neighbour is excluded → correct wall.
            LinkedHashSet<Long> columns = new LinkedHashSet<>(awakeColumns);
            for (long packed : awakeColumns) {
                int cx = unpackCx(packed);
                int cz = unpackCz(packed);
                addLoadedNeighbour(level, columns, cx - 1, cz);
                addLoadedNeighbour(level, columns, cx + 1, cz);
                addLoadedNeighbour(level, columns, cx, cz - 1);
                addLoadedNeighbour(level, columns, cx, cz + 1);
            }

            SectionStore store = stores.store(dim);
            for (long packed : columns) {
                int cx = unpackCx(packed);
                int cz = unpackCz(packed);
                if (LiveMaterials.loadedChunk(level, cx, cz) == null) {
                    continue; // unloaded since selection: absent column = wall
                }
                ColumnAssembler.SectionSource src =
                        columnSource(level, dim, store, lut, mats);
                entries.add(new ThermalWorld.ColumnEntry(dim, cx, cz,
                        ColumnAssembler.assemble(cx, cz, lut, mats.registry(), src)));
            }
        }
        // ---- Drain placement intents into this batch's injection list (spec B3) ----
        // ORDERING IS LOAD-BEARING: this drain MUST run after all ColumnAssembler.assemble
        // calls above. InjectionDrain.applyToColumn stomps the assembled cell
        // back to its recorded incumbent for each injected cell (see InjectionDrain class Javadoc).
        // If this block were moved before the per-column assemble loop, the stomp would target
        // uninitialized arrays and the assembler would subsequently re-seed the new species,
        // fabricating a double-placement. Do NOT reorder.
        List<net.rainbowcreation.orge.engine.EngineInjection> injections = new ArrayList<>();
        List<PendingInjections.Intent> drained = new ArrayList<>();
        for (int columnId = 0; columnId < entries.size(); columnId++) {
            ThermalWorld.ColumnEntry e = entries.get(columnId);
            List<PendingInjections.Intent> colIntents =
                    pendingInjections.peekColumn(e.dimension(), e.cx(), e.cz());
            if (colIntents.isEmpty()) {
                continue;
            }
            SectionStore store = stores.store(e.dimension());
            char[] colMat = e.task().matIx();
            float[] colMass = e.task().mass();
            int injBefore = injections.size();
            // Resolver registers the placed species into the batch LUT (appends if absent) so a
            // just-placed / stomped fluid that is not yet anywhere in the live snapshot is still a
            // valid injection species — the fix for the "placement species not in batch LUT" drop.
            InjectionDrain.SpeciesResolver resolver = id -> {
                Material m = materialById(id, mats);
                return m != null ? lut.indexOf(m) : 0;
            };
            InjectionDrain.applyToColumn(columnId, colMat, colMass, resolver, colIntents,
                    engineCell -> recordedIncumbentId(e.dimension(), e.cx(), e.cz(), engineCell),
                    engineCell -> storedMassAt(store, e.cx(), e.cz(), engineCell),
                    injections, drained);
            if (InjectDebug.on()) {
                int emitted = injections.size() - injBefore;
                InjectDebug.LOG.info("[drain] col=({},{}) intents={} emitted={}{}",
                        e.cx(), e.cz(), colIntents.size(), emitted,
                        emitted < colIntents.size()
                                ? " (emitted<intents => unregistered material, left queued)" : "");
            }
        }
        lutSink.accept(lut.materials());
        return new ThermalWorld.ColumnBatch(entries, lut.materials(), mats.lutEpoch(), injections, drained);
    }

    /** Recorded engine-output species id for an engine-cell in a column, or null if untracked. */
    private Identifier recordedIncumbentId(Identifier dim, int cx, int cz, int engineCell) {
        int x = RegionMarshaller.colX(engineCell);
        int engineY = RegionMarshaller.colY(engineCell);
        int z = RegionMarshaller.colZ(engineCell);
        int sectionY = Math.floorDiv(engineY - 64, 16);      // inverse of engineY = sectionY*16 + sy + 64
        int sy = engineY - 64 - sectionY * 16;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        Identifier[] prior = cellMaterials.prior(dim, key);
        int sectionCell = x + 16 * sy + 256 * z;
        return (prior != null && sectionCell < prior.length) ? prior[sectionCell] : null;
    }

    /** Stored mass (kg) for an engine-cell in a column. */
    private float storedMassAt(SectionStore store, int cx, int cz, int engineCell) {
        if (store == null) {
            return 0f;
        }
        int x = RegionMarshaller.colX(engineCell);
        int engineY = RegionMarshaller.colY(engineCell);
        int z = RegionMarshaller.colZ(engineCell);
        int sectionY = Math.floorDiv(engineY - 64, 16);
        int sy = engineY - 64 - sectionY * 16;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        if (!store.isLoaded(cx, cz)) {
            return 0f;
        }
        SectionData data = store.get(key);
        if (data == null) {
            return 0f;
        }
        int sectionCell = x + 16 * sy + 256 * z;
        return data.massAt(sectionCell);
    }

    /**
     * A per-section reader for {@link ColumnAssembler}: matIx from live blocks (via
     * {@link LiveMaterials}/{@link GeometryAssembler}); mass/T from the {@link SectionStore} (the
     * §10 advected state — the fresh-fluid seed is applied once by {@link ColumnAssembler}, not here),
     * or the per-cell ambient seed for a never-simulated / out-of-world section. A section not loaded
     * in the chunk is read as
     * full ambient air (matIx 0 / void with ambient T) — it is inside a present (loaded) column, so it
     * is a real defined cell, never "unknown".
     */
    private ColumnAssembler.SectionSource columnSource(ServerLevel level, Identifier dim,
                                                       SectionStore store, MaterialLut lut,
                                                       ActiveMaterials.State mats) {
        return (cx, cz, sectionY) -> {
            SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
            LevelChunk chunk = LiveMaterials.loadedChunk(level, cx, cz);
            LevelChunkSection section = chunk == null ? null : LiveMaterials.sectionOrNull(chunk, sectionY);
            float ambientK = BlockChangeCapture.biomeAmbientK(level, key);
            if (section == null) {
                // No block section here (above world top / empty subchunk): treat as void/ambient. matIx
                // 0 (void) → ColumnAssembler leaves it as-is (not a fluid, so no seed); mass 0; ambient T.
                char[] mat = new char[SectionData.CELLS];
                float[] mass = new float[SectionData.CELLS];
                float[] temp = new float[SectionData.CELLS];
                java.util.Arrays.fill(temp, ambientK);
                return new ColumnAssembler.SectionCells(mat, mass, temp);
            }
            final LevelChunkSection sec = section;
            GeometryAssembler.CellMaterials cellMat =
                    i -> LiveMaterials.materialFor(LiveMaterials.blockAt(sec, i), mats.registry());
            GeometryAssembler.Geometry geo = GeometryAssembler.assemble(cellMat, lut);
            float[] mass = sectionMass(store, key, geo, lut);
            // Last cycle's recorded engine-output species (the CellMaterialTracker signature) feeds the
            // ColumnAssembler seed gate and the InjectionDrain incumbent lookup below. Identity itself is
            // now durable (persisted into SectionStore at write-back), so there is no longer a block-diff
            // reseed here — material changes arrive as place/break EVENTS, not a snapshot-time diff.
            Identifier[] priorMat = cellMaterials.prior(dim, key);
            // Translate last cycle's recorded engine-output species into this section's LUT space for the
            // ColumnAssembler seed gate: a fluid cell at 0 kg is reseeded only when its label is NEW
            // relative to priorSpecies (genuine placement), never when the engine drained it (prior ==
            // current ⇒ no fabrication). An untracked section yields all-void ⇒ every fresh fluid seeds.
            char[] priorSpecies = priorSpeciesIndices(priorMat, lut);
            // Durable identity (durable-material §): when this section has a stored material layer, the
            // stored id is AUTHORITATIVE per cell (a broken cell stays orge:vacuum). The assembler resolves
            // it into the batch LUT by appending. Sections with no stored layer keep storedMaterial null ⇒
            // the assembler falls back to the block's first-touch geo.matIx().
            Identifier[] storedMaterial = new Identifier[SectionData.CELLS];
            if (store != null && store.hasSection(key) && store.get(key).hasMaterials()) {
                for (int i = 0; i < SectionData.CELLS; i++) {
                    storedMaterial[i] = store.materialAt(cx, cz, sectionY, i);
                }
            }
            // Extensive momentum + dynamic pressure + STORED absolute E: read per-cell stored values from
            // the SectionStore when available; otherwise zero-fill (never-simulated / back-compat default).
            // Threading p back in is what lets depth-pressure ACCUMULATE across engine steps (the JNI
            // rebuilds a fresh World each call, so without persisting p it would reset to 0 every step).
            // F2 (law §7 / §1.1): SectionCells.momX/Y/Z carry the raw STORED EXTENSIVE momentum p [kg·m/s],
            // sourced loss-free (NO v=p/m down-conversion at this seam) exactly like the stored E channel.
            // S6 (law §6/§7): SectionCells.enthalpy carries the raw STORED extensive E [J] (loss-free to
            // the engine's eIn); the engine's TEMPERATURE channel (tIn diagnostic) carries a DERIVED,
            // CLAMPED T = h⁻¹(E/m) per cell — NEVER the raw E. Never-simulated cells: E = 0 (void/empty).
            float[] momX = new float[SectionData.CELLS];
            float[] momY = new float[SectionData.CELLS];
            float[] momZ = new float[SectionData.CELLS];
            float[] p    = new float[SectionData.CELLS];
            float[] swapReady = new float[SectionData.CELLS];
            float[] enthalpy = new float[SectionData.CELLS];
            // The engine tIn channel: for a stored section it is DERIVED from E below; for a never-
            // simulated section it is the per-cell ambient/source seed.
            float[] temps;
            if (store != null && store.hasSection(key)) {
                SectionData sd = store.get(key);
                java.util.function.Function<Identifier, Material> lookup =
                        id -> mats.registry().get(id).orElse(null);
                boolean hasMaterials = sd.hasMaterials();
                temps = new float[SectionData.CELLS];
                for (int i = 0; i < SectionData.CELLS; i++) {
                    float mi = mass[i];
                    // F2 (law §7 / §1.1): EXTENSIVE momentum p [kg·m/s] crosses RAW (mirror of the stored E
                    // channel) — NO v=p/m down-conversion here, and NO mass guard. A resting/thinned-then-
                    // refilled cell carries its stored p regardless of current mass; a resting massless cell
                    // already has stored p == 0, so the unguarded copy is exact in both cases.
                    momX[i] = sd.momXAt(i);
                    momY[i] = sd.momYAt(i);
                    momZ[i] = sd.momZAt(i);
                    p[i]    = sd.pAt(i);
                    swapReady[i] = sd.swapReadyAt(i);
                    // Raw stored absolute E [J] handed to the engine loss-free (NEVER as Kelvin).
                    enthalpy[i] = sd.enthalpyAt(i);
                    // Derive the engine's diagnostic tIn = h⁻¹(E/m), clamped to the [0,6000] derive
                    // boundary. Per-cell species = stored material when this section carries a durable
                    // layer, else the cell's first-touch block material. A massless / unresolvable cell
                    // derives to ambient (no curve). This is the law-§6/§7 fix: the engine never receives
                    // raw E in its Kelvin slot.
                    Material cellM = hasMaterials ? lookup.apply(sd.materialAt(i)) : cellMat.at(i);
                    float t = (cellM == null) ? ambientK
                            : EnthalpyCurve.deriveT(sd.enthalpyAt(i), mi, cellM, lookup, ambientK);
                    temps[i] = StepValidator.clampDerivedKelvin(t);
                }
            } else {
                temps = AmbientSeeder.seed(cellMat::at, ambientK);
            }
            return new ColumnAssembler.SectionCells(geo.matIx(), mass, temps, priorSpecies, storedMaterial,
                    momX, momY, momZ, p, swapReady, enthalpy);
        };
    }

    /**
     * Input mass for one section: the stored/advected mass when the section has been simulated,
     * otherwise the block-derived geometry seed (a never-tracked section's true initial state — air
     * 1.2 kg, solids/fluids at their {@link Material#defaultMass()}). The fresh-fluid seed (a fluid
     * cell the store reports empty ⇒ {@code defaultMass}) is NOT applied here: it is owned by the
     * single seed in {@link ColumnAssembler} (DESIGN 2026-06-01 §6 — exactly one fresh-fluid seed in
     * the pipeline). This method now only selects stored-vs-geometry, never fabricates fluid mass.
     */
    private float[] sectionMass(SectionStore store, SubchunkKey key,
                                GeometryAssembler.Geometry geo, MaterialLut lut) {
        boolean has = store != null && store.hasSection(key);
        // Never-simulated section: block-derived initial state (includes air/solid masses ColumnAssembler
        // cannot reconstruct). Simulated section: the advected/stored mass verbatim — the fresh-fluid
        // seed for a stored-empty fluid cell is applied downstream by ColumnAssembler, not here.
        return has ? store.get(key).massArray().clone() : geo.mass();
    }

    /**
     * The prior cycle's recorded engine-output species (a per-cell {@link Identifier} signature from
     * {@link CellMaterialTracker}) translated into the current batch LUT's index space, for the
     * {@link ColumnAssembler} seed gate. A {@code null} prior (never-tracked section) or an id absent
     * from this batch's LUT maps to 0 (void) — which differs from any real fluid index, so a genuinely
     * new placement still seeds. Same {@code char[SectionData.CELLS]} layout the assembler expects.
     */
    private static char[] priorSpeciesIndices(Identifier[] prior, MaterialLut lut) {
        char[] out = new char[SectionData.CELLS];
        if (prior == null) {
            return out; // all void → no recorded signature → every fresh fluid is a genuine placement
        }
        int n = Math.min(prior.length, out.length);
        for (int i = 0; i < n; i++) {
            if (prior[i] != null) {
                out[i] = lut.indexOf(prior[i]);
            }
        }
        return out;
    }

    private void addForcedSections(ServerLevel level, Set<SubchunkKey> union) {
        for (long packed : level.getForceLoadedChunks().toLongArray()) {
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            LevelChunk chunk = LiveMaterials.loadedChunk(level, cx, cz);
            if (chunk == null) continue;
            int min = chunk.getMinSectionY();
            int count = chunk.getSectionsCount();
            for (int s = 0; s < count; s++) {
                union.add(new SubchunkKey(cx, min + s, cz));
            }
        }
    }

    private void addLoadedNeighbour(ServerLevel level, Set<Long> columns, int cx, int cz) {
        if (LiveMaterials.loadedChunk(level, cx, cz) != null) {
            columns.add(packColumn(cx, cz));
        }
    }

    /** id→{@link Material} in the active state, or {@code null} when the id is absent. */
    private static Material materialById(Identifier id, ActiveMaterials.State mats) {
        return mats.registry().get(id).orElse(null);
    }

    private static long packColumn(int cx, int cz) {
        return (cx & 0xffffffffL) | (((long) cz) << 32);
    }
    private static int unpackCx(long packed) { return (int) (packed & 0xffffffffL); }
    private static int unpackCz(long packed) { return (int) (packed >> 32); }
}
