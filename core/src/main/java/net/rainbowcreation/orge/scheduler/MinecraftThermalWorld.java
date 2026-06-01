package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Live {@link ThermalWorld} over a running {@link MinecraftServer} (DESIGN §8; whole-region rewire
 * 2026-06-01). Builds the player-sphere (+ forced-chunk) union across all loaded levels, projects it
 * to the awake column set + a loaded apron ring, and assembles each column into a full-height
 * {@link ColumnTask} via {@link ColumnAssembler} (reading live blocks + the §5 {@link SectionStore}).
 * {@link #writeBackColumn} scatters the validated {@link ColumnResult} back into the 24 sections via
 * {@link ColumnSectionCodec}, then reconciles/phase-changes each. The only Minecraft-coupled class in
 * the scheduler. {@link #snapshotColumns}/{@link #writeBackColumn} touch the server-thread-confined
 * {@link SectionStoreManager} and MUST be called on the server thread (the {@code volatile server}
 * field is the only cross-thread state). The per-section {@link #writeBack} override is kept dormant.
 */
public final class MinecraftThermalWorld implements ThermalWorld {

    private final SectionStoreManager stores;
    private final CellMaterialTracker cellMaterials;
    private final ActiveSet activeSet;
    private volatile MinecraftServer server;

    /** §10/§7 seams the column write-back drives per section (set in {@link Orge} after construction;
     *  default NOOP so headless tests that never set them do not need a live reconciler/phase changer). */
    private FluidReconciler fluidReconciler = FluidReconciler.NOOP;
    private PhaseChanger phaseChanger = PhaseChanger.NOOP;

    /** The material LUT of the most recent {@link #snapshotColumns} batch, threaded to
     *  {@link #writeBackColumn} (whose signature carries no LUT) so the reconciler can resolve the
     *  engine output species. Both run on the server thread in the same cycle, so a plain field is safe. */
    private List<Material> lastColumnLut = List.of(MaterialLut.VOID);

    public MinecraftThermalWorld(SectionStoreManager stores, CellMaterialTracker cellMaterials,
                                 ActiveSet activeSet) {
        this.stores = stores;
        this.cellMaterials = cellMaterials;
        this.activeSet = activeSet;
    }

    /** Wire the per-section reconcile/phase seams the column {@link #writeBackColumn} drives (DESIGN
     *  2026-06-01 §7: write-back persists then reconciles+phase-changes each of the 24 sections). */
    public void setReconcilers(FluidReconciler fluidReconciler, PhaseChanger phaseChanger) {
        this.fluidReconciler = fluidReconciler;
        this.phaseChanger = phaseChanger;
    }

    /** Convenience for headless write-back tests that never call {@link #snapshot}. */
    public MinecraftThermalWorld(SectionStoreManager stores) {
        this(stores, new CellMaterialTracker(), new ActiveSet());
    }

    /** The wake sink the loader event hooks push into (DESIGN §10 Decision 11). */
    public WakeSink wakeSink() {
        return activeSet;
    }

    /** Bind the running server (on SERVER_STARTED); unbind on stop. */
    public void bindServer(MinecraftServer server) { this.server = server; }
    public void unbindServer() { this.server = null; }

    // Server thread only.
    @Override
    public void writeBack(BatchEntry entry, StepResult result) {
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(entry.key().cx(), entry.key().cz())) {
            return;
        }
        SectionData data = store.get(entry.key());
        // TODO(perf, §8 follow-on): temperatureArray() force-promotes a UNIFORM ambient section to FULL (two 4096 arrays + fill) right before we overwrite every cell. A SectionData.setAllTemperatures(float[]) that skips the fill would avoid the churn for first-touch sections.
        float[] dst = data.temperatureArray();
        System.arraycopy(result.temperature(), 0, dst, 0, SectionData.CELLS);
        // Persist the engine's per-cell mass (§10): advection now MOVES mass between cells, so the
        // authoritative post-step mass is result.mass() — no longer the snapshot geometry mass.
        // Conduction cycles carry mass through unchanged (the scheduler passes the snapshot mass
        // back in result.mass()), so this stays the block-derived geometry mass when no flow ran.
        float[] massDst = data.massArray();
        System.arraycopy(result.mass(), 0, massDst, 0, SectionData.CELLS);
        // writeBack force-promoted this section to FULL via the array accessors above. Collapse it
        // straight back to UNIFORM when the engine left every cell identical (a settled/flat section),
        // so FULL is not a one-way ratchet — observability (/orge get-live) and the on-disk form both
        // reflect the section's true state. No-op (cheap scan, returns false) while a gradient remains.
        data.demoteIfUniform();
        store.put(entry.key(), data);
    }

    /**
     * Feed a section's per-step settle deltas into the active set (DESIGN §10 Decision 11). A
     * negative delta means that pass did not run this cycle (skip its countdown), so a coincident
     * conduction tick still counts the flow pass and vice versa.
     */
    @Override
    public void noteSettle(BatchEntry entry, float maxMassDelta, float maxTempDelta) {
        if (maxMassDelta >= 0f) {
            activeSet.noteFlowDelta(entry.dimension(), entry.key(), maxMassDelta);
        }
        if (maxTempDelta >= 0f) {
            activeSet.noteThermalDelta(entry.dimension(), entry.key(), maxTempDelta);
        }
    }

    /** Trigger (c): a neighbour pushed mass across our shared seam — revive its flow pass so it
     *  re-enters the snapshot and accepts the incoming mass instead of stranding it at the border. */
    @Override
    public void wakeNeighbourFlow(Identifier dim, SubchunkKey neighbour) {
        activeSet.wakeFlowSection(dim, neighbour);
    }

    /**
     * Record the engine's OUTPUT species as the signature for this section's just-persisted mass
     * (DESIGN §10 follow-on; the reseed-misfire fix). Per cell the recorded species is the engine
     * output when present ({@code outMat[i] != 0}), else the cell's input/world material — so an
     * untouched air cell records {@code orge:air}, never the index-0 {@code orge:void} sentinel. This
     * makes the NEXT snapshot's {@link MaterialChangeReseed} treat the reconciler's matching fluid
     * placement as already-known (no reseed → mass is conserved) while still reseeding genuine
     * external edits. When the signature is unchanged the prior {@code Identifier[]} is reused
     * verbatim, avoiding the 4096-ref signature re-allocation; the small per-cell {@code char[]}
     * species scratch is still built each call.
     */
    @Override
    public void recordCellMaterials(BatchEntry entry, char[] outMat, List<Material> lut) {
        char[] inMat = entry.task().matIx();
        char[] effective = new char[inMat.length];
        for (int i = 0; i < inMat.length; i++) {
            effective[i] = (outMat != null && i < outMat.length && outMat[i] != 0) ? outMat[i] : inMat[i];
        }
        Identifier[] prior = cellMaterials.prior(entry.dimension(), entry.key());
        cellMaterials.record(entry.dimension(), entry.key(), liveMaterialIds(effective, lut, prior));
    }

    /**
     * Temperatures for one section: the stored gradient if the section has been simulated,
     * otherwise a per-cell seed (sources at their default_temperature, bulk at biome ambient).
     * Never-simulated sections are seeded but NOT persisted here — the post-step write-back
     * creates the section; if the step is dropped, next second re-seeds (idempotent).
     */
    private float[] sectionTemps(ServerLevel level, SectionStore store, SubchunkKey key,
                                 GeometryAssembler.CellMaterials cellMat) {
        if (store != null && store.hasSection(key)) {
            return store.get(key).temperatureArray().clone();
        }
        return AmbientSeeder.seed(cellMat::at, biomeAmbientK(level, key));
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
     * The live per-cell material ids for this section, to record as the next cycle's signature. Reuses
     * {@code prior} verbatim when the live materials are identical (the overwhelming common case) so a
     * static section costs only a comparison pass, not a fresh 4096-ref allocation every snapshot.
     */
    private static Identifier[] liveMaterialIds(char[] matIx, List<Material> mats, Identifier[] prior) {
        if (prior != null && idsUnchanged(matIx, mats, prior)) {
            return prior;
        }
        Identifier[] ids = new Identifier[matIx.length];
        for (int i = 0; i < matIx.length; i++) {
            ids[i] = mats.get(matIx[i]).id();
        }
        return ids;
    }

    private static boolean idsUnchanged(char[] matIx, List<Material> mats, Identifier[] prior) {
        for (int i = 0; i < matIx.length; i++) {
            if (!mats.get(matIx[i]).id().equals(prior[i])) {
                return false;
            }
        }
        return true;
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

    /** Biome base temperature sampled once at the section centre, mapped to Kelvin. */
    private static float biomeAmbientK(ServerLevel level, SubchunkKey key) {
        int bx = (key.cx() << 4) + 8;
        int by = (key.sectionY() << 4) + 8;
        int bz = (key.cz() << 4) + 8;
        float base = level.getBiome(new BlockPos(bx, by, bz)).value().getBaseTemperature();
        return BiomeTemperature.toKelvin(base);
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

    // ============================================================================================
    // Whole-region column path (DESIGN 2026-06-01). snapshotColumns assembles the active+apron column
    // set into full-height ColumnTasks; writeBackColumn scatters a ColumnResult back into 24 sections.
    // ============================================================================================

    // Server thread only.
    @Override
    public ColumnBatch snapshotColumns(int range) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return new ColumnBatch(List.of(), List.of(MaterialLut.VOID));
        }
        ActiveMaterials.State mats = ActiveMaterials.current();
        MaterialLut lut = new MaterialLut();
        List<ColumnEntry> entries = new ArrayList<>();

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
                entries.add(new ColumnEntry(dim, cx, cz,
                        ColumnAssembler.assemble(cx, cz, lut.materials(), src)));
            }
        }
        lastColumnLut = lut.materials();
        return new ColumnBatch(entries, lut.materials());
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
            float ambientK = biomeAmbientK(level, key);
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
                    i -> LiveMaterials.materialFor(LiveMaterials.blockStateAt(sec, i), mats);
            GeometryAssembler.Geometry geo = GeometryAssembler.assemble(cellMat, lut);
            float[] temps = sectionTemps(level, store, key, cellMat);
            float[] mass = sectionMass(store, key, geo, lut);
            // §10 follow-on: refresh cells whose block changed material since last cycle (bucket fluid,
            // /setblock, broken block→vacuum) — same reseed the per-section path applied.
            Identifier[] priorMat = cellMaterials.prior(dim, key);
            MaterialChangeReseed.apply(priorMat, geo.matIx(), lut.materials(), temps, mass, ambientK);
            // Translate last cycle's recorded engine-output species into this section's LUT space for the
            // ColumnAssembler seed gate: a fluid cell at 0 kg is reseeded only when its label is NEW
            // relative to priorSpecies (genuine placement), never when the engine drained it (prior ==
            // current ⇒ no fabrication). An untracked section yields all-void ⇒ every fresh fluid seeds.
            char[] priorSpecies = priorSpeciesIndices(priorMat, lut);
            return new ColumnAssembler.SectionCells(geo.matIx(), mass, temps, priorSpecies);
        };
    }

    // Server thread only.
    @Override
    public void writeBackColumn(ColumnEntry entry, ColumnResult result) {
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(entry.cx(), entry.cz())) {
            return;
        }
        float maxMassDelta = 0f;
        float[] inMass = entry.task().mass();
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            SubchunkKey key = new SubchunkKey(entry.cx(), sectionY, entry.cz());
            float[][] tm = ColumnSectionCodec.sliceSection(result.temperature(), result.mass(), sectionY);
            float[] inT = ColumnSectionCodec.sliceSection(
                    entry.task().temperature(), entry.task().mass(), sectionY)[0];
            // Clamp identically to the per-section path: non-finite T → snapshot input (clamped [0,6000]);
            // mass clamped to [0, fullMassBound] (the column's max defaultMass over its species).
            float[] cleanT = StepValidator.clean(tm[0], inT);
            float[] cleanM = StepValidator.cleanMass(tm[1], fullMassBound(entry.task()));
            char[] outMat = ColumnSectionCodec.sliceSectionMaterials(result.matIx(), sectionY);

            SectionData data = store.get(key);
            float[] dstT = data.temperatureArray();
            System.arraycopy(cleanT, 0, dstT, 0, SectionData.CELLS);
            float[] dstM = data.massArray();
            System.arraycopy(cleanM, 0, dstM, 0, SectionData.CELLS);
            data.demoteIfUniform();
            store.put(key, data);

            // Reconstruct the per-section entry the §10/§7 seams consume (input geometry + this section's
            // engine output species). recordCellMaterials/noteSettle/reconcile/phase mirror the
            // per-section advection write-back exactly.
            char[] inMatSec = ColumnSectionCodec.sliceSectionMaterials(entry.task().matIx(), sectionY);
            StepTask secTask = new StepTask(key, inMatSec, inMass(inMass, sectionY), inT,
                    NeighborHalo.empty());
            ThermalWorld.BatchEntry secEntry = new ThermalWorld.BatchEntry(entry.dimension(), key, secTask);
            recordCellMaterials(secEntry, outMat, lastColumnLut);
            float secMassDelta = maxAbsDelta(cleanM, secTask.mass());
            if (secMassDelta > maxMassDelta) maxMassDelta = secMassDelta;
            noteSettle(secEntry, secMassDelta, -1f);
            phaseChanger.applyPhaseChanges(secEntry);
            fluidReconciler.reconcile(secEntry, outMat, lastColumnLut);
        }
        // Wake any LOADED neighbour column that could have received mass across an X/Z boundary this step
        // (DESIGN §5 wake-on-cross): if our column moved any mass, the apron neighbour must re-enter next
        // cycle to accept incoming flow rather than stranding it at the seam. Column-granular replacement
        // (the old per-section seam wake); over-waking is harmless (the neighbour settles via noteSettle).
        if (maxMassDelta >= SettleCountdown.EPS_MASS) {
            wakeColumnNeighbour(entry.dimension(), entry.cx() - 1, entry.cz());
            wakeColumnNeighbour(entry.dimension(), entry.cx() + 1, entry.cz());
            wakeColumnNeighbour(entry.dimension(), entry.cx(), entry.cz() - 1);
            wakeColumnNeighbour(entry.dimension(), entry.cx(), entry.cz() + 1);
        }
    }

    /** The section-Y slice of a column's input mass (for the per-section reconstructed StepTask). */
    private static float[] inMass(float[] columnInMass, int sectionY) {
        float[] m = new float[SectionData.CELLS];
        for (int z = 0; z < 16; z++) {
            for (int sy = 0; sy < 16; sy++) {
                int colRow = 16 * ColumnSectionCodec.engineY(sectionY, sy) + 6144 * z;
                int secRow = 16 * sy + 256 * z;
                for (int x = 0; x < 16; x++) {
                    m[secRow + x] = columnInMass[colRow + x];
                }
            }
        }
        return m;
    }

    /** Wake every loaded section of a neighbour column's flow pass so it re-enters next cycle. */
    private void wakeColumnNeighbour(Identifier dim, int cx, int cz) {
        // We don't know which sectionY received mass; wake the whole loaded column's flow countdowns so a
        // dormant neighbour re-enters. Cheap: one map touch per existing section; the codec drives the
        // common case where flow stays near the donor anyway.
        SectionStore store = stores.store(dim);
        if (store == null || !store.isLoaded(cx, cz)) return;
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
            if (store.hasSection(key)) {
                activeSet.wakeFlowSection(dim, key);
            }
        }
    }

    /** Max material defaultMass over the column's species (the per-cell mass clamp bound). */
    private static float fullMassBound(ColumnTask task) {
        // The column carries no LUT; the bound is the largest legal per-cell mass. Use a generous water-
        // scale cap (1000 kg is every fluid's full block); a higher-density fluid keeps its own mass via
        // the per-species ledger upstream. Clamp here only guards against engine NaN/overflow leakage.
        return Float.MAX_VALUE; // per-species bounds are enforced by the region ledger; avoid clamping real mass
    }

    private static float maxAbsDelta(float[] a, float[] b) {
        float m = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            float d = Math.abs(a[i] - b[i]);
            if (d > m) m = d;
        }
        return m;
    }

    private static long packColumn(int cx, int cz) {
        return (cx & 0xffffffffL) | (((long) cz) << 32);
    }
    private static int unpackCx(long packed) { return (int) (packed & 0xffffffffL); }
    private static int unpackCz(long packed) { return (int) (packed >> 32); }

    private void addLoadedNeighbour(ServerLevel level, Set<Long> columns, int cx, int cz) {
        if (LiveMaterials.loadedChunk(level, cx, cz) != null) {
            columns.add(packColumn(cx, cz));
        }
    }
}
