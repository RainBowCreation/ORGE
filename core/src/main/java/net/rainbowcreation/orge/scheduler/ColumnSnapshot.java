package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Range in &rarr; {@link ThermalWorld.ColumnBatch} out for the live {@link MinecraftThermalWorld}
 * (DESIGN 2026-06-01 §5–§6). Builds the player-sphere (+ forced-chunk) union across all loaded levels,
 * projects it to the awake column set + a loaded apron ring, assembles each surviving column into a
 * full-height {@link net.rainbowcreation.orge.engine.ColumnTask} via {@link ColumnAssembler} (reading
 * live blocks + the §5 {@link SectionStore}), then drains this cycle's placement intents into the
 * batch's injection list (spec B3).
 *
 * <p>Split across threads (issue #51): {@link #capture} runs on the server thread — the selection plus a
 * copy of what the assembly reads ({@link CapturedColumn}), no per-cell work — and {@link #assemble} builds
 * the batch from that capture on the scheduler's step thread. {@link #snapshotColumns} does both on the
 * calling thread and publishes the batch LUT through {@code lutSink} for the in-place write-back. Reads the
 * live {@link MinecraftServer} lazily through {@code serverSource}; the capture is empty when the server is
 * unbound or there is nothing in range.</p>
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

    /** This cycle's capture: the active material state and the captured columns, assembled off-thread.
     *  A null state is the capture with no server bound: no columns, and the empty batch. */
    final class Captured implements ThermalWorld.CapturedColumns {
        private final ActiveMaterials.State mats;
        private final List<CapturedColumn> columns;

        Captured(ActiveMaterials.State mats, List<CapturedColumn> columns) {
            this.mats = mats;
            this.columns = columns;
        }

        List<CapturedColumn> columns() { return columns; }

        @Override public boolean isEmpty() { return columns.isEmpty(); }

        @Override public ThermalWorld.ColumnBatch assemble() {
            return mats == null ? new ThermalWorld.ColumnBatch(List.of(), List.of(MaterialLut.VACUUM))
                    : ColumnSnapshot.this.assemble(mats, columns);
        }
    }

    /** Capture and assemble in one go, on the calling (server) thread, publishing the batch LUT. */
    ThermalWorld.ColumnBatch snapshotColumns(int range) {
        ThermalWorld.ColumnBatch batch = capture(range).assemble();
        lutSink.accept(batch.lut());
        return batch;
    }

    /**
     * Server-thread half of the snapshot (issue #51): select this cycle's columns and copy what the
     * assembly reads — per section the live block states, the store section (stamping its edit epoch) and
     * the recorded engine-output species, per column its queued placement intents. No per-cell work.
     */
    Captured capture(int range) {
        MinecraftServer srv = serverSource.get();
        if (srv == null) {
            return new Captured(null, List.of());
        }
        ActiveMaterials.State mats = ActiveMaterials.current();
        List<CapturedColumn> captured = new ArrayList<>();

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
                LevelChunk chunk = LiveMaterials.loadedChunk(level, cx, cz);
                if (chunk == null) {
                    continue; // unloaded since selection: absent column = wall
                }
                captured.add(captureColumn(level, dim, store, chunk, cx, cz));
            }
        }
        return new Captured(mats, captured);
    }

    /** Copy one column's 24 sections (block states, store section, recorded species) and its intents. */
    private CapturedColumn captureColumn(ServerLevel level, Identifier dim, SectionStore store, LevelChunk chunk,
                                         int cx, int cz) {
        boolean storeLoaded = store != null && store.isLoaded(cx, cz);
        CapturedColumn.Section[] sections =
                new CapturedColumn.Section[ColumnAssembler.MAX_SECTION_Y - ColumnAssembler.MIN_SECTION_Y + 1];
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
            LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, sectionY);
            boolean stored = store != null && store.hasSection(key);
            SectionData data = null;
            if (storeLoaded) {
                SectionData live = store.get(key);
                if (stored) {
                    // Stamp the edit-epoch: the write-back of THIS cycle will skip the section if an
                    // external edit (/orge set) lands after this read, so it can't clobber the edit.
                    live.markSnapshot();
                }
                data = live.share(); // copy-on-write view: no copy unless the live section changes
            }
            sections[sectionY - ColumnAssembler.MIN_SECTION_Y] = new CapturedColumn.Section(
                    section == null ? null : section.getStates().copy(),
                    BlockChangeCapture.biomeAmbientK(level, key), cellMaterials.prior(dim, key), data, stored);
        }
        return new CapturedColumn(dim, cx, cz, sections, pendingInjections.peekColumn(dim, cx, cz));
    }

    /**
     * Off-thread half of the snapshot (issue #51): assemble each captured column into a full-height
     * {@link net.rainbowcreation.orge.engine.ColumnTask} via {@link ColumnAssembler}, then drain the
     * captured placement intents into the batch's injection list (spec B3). Reads only the capture.
     */
    ThermalWorld.ColumnBatch assemble(ActiveMaterials.State mats, List<CapturedColumn> columns) {
        MaterialLut lut = new MaterialLut(mats.orderedMaterials(), mats.materialSlots());
        // First-touch material per block, resolved once per batch (it builds an id per call).
        Map<Block, Material> firstTouch = new HashMap<>();
        List<ThermalWorld.ColumnEntry> entries = new ArrayList<>(columns.size());
        for (CapturedColumn c : columns) {
            WaterlogSeeding.Seeds waterlog = new WaterlogSeeding.Seeds();
            entries.add(new ThermalWorld.ColumnEntry(c.dimension, c.cx, c.cz,
                    ColumnAssembler.assemble(c.cx, c.cz, lut, mats.registry(),
                            capturedSource(c, lut, mats, firstTouch), waterlog),
                    waterlog, c));
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
            CapturedColumn c = e.captured();
            List<PendingInjections.Intent> colIntents = c.intents;
            if (colIntents.isEmpty()) {
                continue;
            }
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
                    engineCell -> recordedIncumbentId(c, engineCell),
                    engineCell -> storedMassAt(c, engineCell),
                    injections, drained);
            if (InjectDebug.on()) {
                int emitted = injections.size() - injBefore;
                InjectDebug.LOG.info("[drain] col=({},{}) intents={} emitted={}{}",
                        e.cx(), e.cz(), colIntents.size(), emitted,
                        emitted < colIntents.size()
                                ? " (emitted<intents => unregistered material, left queued)" : "");
            }
        }
        return new ThermalWorld.ColumnBatch(entries, lut.materials(), mats.lutEpoch(), injections, drained);
    }

    /** Recorded engine-output species id for an engine-cell of a captured column, or null if untracked. */
    private static Identifier recordedIncumbentId(CapturedColumn c, int engineCell) {
        int engineY = RegionMarshaller.colY(engineCell);
        int sectionY = Math.floorDiv(engineY - 64, 16);      // inverse of engineY = sectionY*16 + sy + 64
        int sy = engineY - 64 - sectionY * 16;
        Identifier[] prior = c.section(sectionY).prior();
        int sectionCell = RegionMarshaller.colX(engineCell) + 16 * sy + 256 * RegionMarshaller.colZ(engineCell);
        return (prior != null && sectionCell < prior.length) ? prior[sectionCell] : null;
    }

    /** Stored mass (kg) for an engine-cell of a captured column (0 when its store was not loaded). */
    private static float storedMassAt(CapturedColumn c, int engineCell) {
        int engineY = RegionMarshaller.colY(engineCell);
        int sectionY = Math.floorDiv(engineY - 64, 16);
        int sy = engineY - 64 - sectionY * 16;
        SectionData data = c.section(sectionY).data();
        if (data == null) {
            return 0f;
        }
        return data.massAt(RegionMarshaller.colX(engineCell) + 16 * sy + 256 * RegionMarshaller.colZ(engineCell));
    }

    /**
     * A per-section reader for {@link ColumnAssembler} over a captured column: matIx from the captured
     * blocks (via {@link LiveMaterials}/{@link GeometryAssembler}); mass/T from the captured store section
     * (the §10 advected state — the fresh-fluid seed is applied once by {@link ColumnAssembler}, not here),
     * or the per-cell ambient seed for a never-simulated / out-of-world section. A section the chunk does
     * not have is read as full ambient air (matIx 0 / void with ambient T) — it is inside a present
     * (loaded) column, so it is a real defined cell, never "unknown".
     */
    private static ColumnAssembler.SectionSource capturedSource(CapturedColumn c, MaterialLut lut,
                                                                ActiveMaterials.State mats,
                                                                Map<Block, Material> firstTouch) {
        Material water = mats.registry().get(ColumnAssembler.WATER_ID).orElse(null);
        return (cx, cz, sectionY) -> {
            CapturedColumn.Section s = c.section(sectionY);
            float ambientK = s.ambientK();
            if (s.blocks() == null) {
                // No block section here (above world top / empty subchunk): treat as void/ambient. matIx
                // 0 (void) → ColumnAssembler leaves it as-is (not a fluid, so no seed); mass 0; ambient T.
                char[] mat = new char[SectionData.CELLS];
                float[] mass = new float[SectionData.CELLS];
                float[] temp = new float[SectionData.CELLS];
                java.util.Arrays.fill(temp, ambientK);
                return new ColumnAssembler.SectionCells(mat, mass, temp);
            }
            // A waterlogged live block holds ρ_water·V_pore of water (proposal §12 Q4 (B)); the assembler
            // seeds it once (a never-simulated or legacy cell), never into a drained cell.
            Material[] blockMat = new Material[SectionData.CELLS];
            float[] waterlogSeed = new float[SectionData.CELLS];
            for (int i = 0; i < SectionData.CELLS; i++) {
                BlockState st = s.blockAt(i);
                blockMat[i] = firstTouch.computeIfAbsent(st.getBlock(),
                        b -> LiveMaterials.materialFor(b, mats.registry()));
                if (st.hasProperty(BlockStateProperties.WATERLOGGED) && st.getValue(BlockStateProperties.WATERLOGGED)) {
                    waterlogSeed[i] = ColumnAssembler.waterlogSeedKg(blockMat[i], water);
                }
            }
            GeometryAssembler.CellMaterials cellMat = i -> blockMat[i];
            GeometryAssembler.Geometry geo = GeometryAssembler.assemble(cellMat, lut);
            // Last cycle's recorded engine-output species (the CellMaterialTracker signature) feeds the
            // ColumnAssembler seed gate and the InjectionDrain incumbent lookup. Identity itself is durable
            // (persisted into SectionStore at write-back), so there is no longer a block-diff reseed here —
            // material changes arrive as place/break EVENTS, not a snapshot-time diff.
            // Translated into this batch's LUT space for the seed gate: a fluid cell at 0 kg is reseeded
            // only when its label is NEW relative to priorSpecies (genuine placement), never when the engine
            // drained it (prior == current ⇒ no fabrication). An untracked section yields all-void ⇒ every
            // fresh fluid seeds.
            char[] priorSpecies = priorSpeciesIndices(s.prior(), lut);
            return SectionCellsReader.read(s.stored() ? s.data() : null, geo, cellMat,
                    id -> mats.registry().get(id).orElse(null), priorSpecies, waterlogSeed, ambientK);
        };
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
