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
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Live {@link ThermalWorld} over a running {@link MinecraftServer} (DESIGN §8). Builds the
 * player-sphere (+ forced-chunk) union across all loaded levels, assembles each loaded
 * section's {@link StepTask} via the pure {@link SphereUnion}/{@link GeometryAssembler}/
 * {@link HaloAssembler} units, and writes validated results back through the §5
 * {@link SectionStoreManager}. The only Minecraft-coupled class in the scheduler.
 * Both {@link #snapshot} and {@link #writeBack} touch the server-thread-confined
 * {@link SectionStoreManager} and MUST be called on the server thread (the
 * {@code volatile server} field is the only cross-thread state).
 */
public final class MinecraftThermalWorld implements ThermalWorld {

    private final SectionStoreManager stores;
    private final CellMaterialTracker cellMaterials;
    private final ActiveSet activeSet;
    private volatile MinecraftServer server;

    public MinecraftThermalWorld(SectionStoreManager stores, CellMaterialTracker cellMaterials,
                                 ActiveSet activeSet) {
        this.stores = stores;
        this.cellMaterials = cellMaterials;
        this.activeSet = activeSet;
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
    public Batch snapshot(int range) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return new Batch(List.of(), List.of(MaterialLut.VOID));
        }
        ActiveMaterials.State mats = ActiveMaterials.current();
        MaterialLut lut = new MaterialLut();
        List<BatchEntry> entries = new ArrayList<>();

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

            // §10 Decision 11: step only the ACTIVE SET within range. A never-seen section is
            // admitted active (new-in-range); a fully-asleep section is dropped here so a calm
            // ocean stops re-simulating. activeWithin both filters and records new-in-range keys;
            // the rest of the loop body (geometry, temps, mass, halo, entries.add) is unchanged.
            List<SubchunkKey> active = activeSet.activeWithin(dim, new ArrayList<>(union));

            for (SubchunkKey key : active) {
                LevelChunk chunk = LiveMaterials.loadedChunk(level, key.cx(), key.cz());
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, key.sectionY());
                if (section == null) {
                    continue;
                }
                final LevelChunkSection sec = section;
                GeometryAssembler.CellMaterials cellMat =
                        i -> LiveMaterials.materialFor(LiveMaterials.blockStateAt(sec, i), mats);
                GeometryAssembler.Geometry geo = GeometryAssembler.assemble(cellMat, lut);
                SectionStore store = stores.store(dim);
                float[] temps = sectionTemps(level, store, key, cellMat);
                float[] mass = sectionMass(store, key, geo, lut);
                // §10 follow-on: a cell whose block changed material since last cycle (bucket fluid,
                // /setblock, piston) still carries the OLD block's persisted temp/mass (a formerly-air
                // cell stored 1.2 kg + ambient). Refresh those stale cells from the new material's
                // defaults, then record this cycle's live materials so the next snapshot can detect
                // the next change. record reuses the prior array when nothing changed (no allocation).
                Identifier[] priorMat = cellMaterials.prior(dim, key);
                MaterialChangeReseed.apply(priorMat, geo.matIx(), lut.materials(), temps, mass,
                        biomeAmbientK(level, key));
                cellMaterials.record(dim, key, liveMaterialIds(geo.matIx(), lut.materials(), priorMat));
                NeighborHalo halo = buildHalo(level, dim, key, lut, mats);
                entries.add(new BatchEntry(dim, key,
                        new StepTask(key, geo.matIx(), mass, temps, halo)));
            }
        }
        return new Batch(entries, lut.materials());
    }

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
     * Input mass for one section (§10 advection): the stored/advected mass when the section has
     * been simulated, otherwise the block-derived geometry seed. When the store exists, fluid
     * cells the store reports empty are seeded once to full (the freshly-placed-fluid entry
     * point) via {@link MassSnapshot} — the SAME rule the halo {@link #neighbor} mass uses, so
     * the two sides of a section face always agree on a cell's mass.
     */
    private float[] sectionMass(SectionStore store, SubchunkKey key,
                                GeometryAssembler.Geometry geo, MaterialLut lut) {
        boolean has = store != null && store.hasSection(key);
        float[] stored = has ? store.get(key).massArray().clone() : null;
        return MassSnapshot.selectAll(stored, geo.matIx(), lut.materials(), has, geo.mass());
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

    /** Build the 6-face halo: neighbour temps from the store, neighbour matIx from geometry. */
    private NeighborHalo buildHalo(ServerLevel level, Identifier dim, SubchunkKey k,
                                   MaterialLut lut, ActiveMaterials.State mats) {
        return HaloAssembler.assemble(
                neighbor(level, dim, k.cx() - 1, k.sectionY(), k.cz(), lut, mats, GeometryAssembler.Face.NEG_X),
                neighbor(level, dim, k.cx() + 1, k.sectionY(), k.cz(), lut, mats, GeometryAssembler.Face.POS_X),
                neighbor(level, dim, k.cx(), k.sectionY() - 1, k.cz(), lut, mats, GeometryAssembler.Face.NEG_Y),
                neighbor(level, dim, k.cx(), k.sectionY() + 1, k.cz(), lut, mats, GeometryAssembler.Face.POS_Y),
                neighbor(level, dim, k.cx(), k.sectionY(), k.cz() - 1, lut, mats, GeometryAssembler.Face.NEG_Z),
                neighbor(level, dim, k.cx(), k.sectionY(), k.cz() + 1, lut, mats, GeometryAssembler.Face.POS_Z));
    }

    /**
     * A neighbour's face data (temperature, matIx, mass) or null (void) when not loaded. Only the
     * single 16×16 plane the halo reads ({@code face}) is assembled — {@link GeometryAssembler#assembleFace}
     * computes matIx/mass for those 256 cells, ~16× less geometry work than a full section assemble.
     * At those face cells the values are bit-identical to the old full-section path, so the halo
     * (and cross-section conservation) is unchanged.
     */
    private HaloAssembler.Neighbor neighbor(ServerLevel level, Identifier dim,
                                            int cx, int sectionY, int cz,
                                            MaterialLut lut, ActiveMaterials.State mats,
                                            GeometryAssembler.Face face) {
        LevelChunk chunk = LiveMaterials.loadedChunk(level, cx, cz);
        if (chunk == null) return null;
        LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, sectionY);
        if (section == null) return null;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        SectionStore store = stores.store(dim);
        final LevelChunkSection sec = section;
        GeometryAssembler.CellMaterials cellMat =
                i -> LiveMaterials.materialFor(LiveMaterials.blockStateAt(sec, i), mats);
        float[] temps = sectionTemps(level, store, key, cellMat);
        // Assemble ONLY the contributing face plane (the 256 cells the halo will read), not all 4096.
        GeometryAssembler.Geometry geo = GeometryAssembler.assembleFace(cellMat, lut, face);
        // Neighbour mass: the SAME §10 MassSnapshot rule as the section's own mass (sectionMass) so
        // the two sides of a shared face agree — stored/advected mass when the section exists (fluid
        // entry point seeds empty fluid cells once), else the block-derived geometry seed. Applied
        // only at the face cells (selectFace) to match the face-only geometry.
        boolean has = store != null && store.hasSection(key);
        float[] stored = has ? store.get(key).massArray().clone() : null;
        float[] mass = MassSnapshot.selectFace(stored, geo.matIx(), lut.materials(), has, geo.mass(), face);
        return new HaloAssembler.Neighbor(temps, geo.matIx(), mass);
    }
}
