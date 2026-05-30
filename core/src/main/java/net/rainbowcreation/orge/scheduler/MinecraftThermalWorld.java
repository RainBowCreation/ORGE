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
    private volatile MinecraftServer server;

    public MinecraftThermalWorld(SectionStoreManager stores) {
        this.stores = stores;
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

            for (SubchunkKey key : union) {
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
                NeighborHalo halo = buildHalo(level, dim, key, lut, mats);
                entries.add(new BatchEntry(dim, key,
                        new StepTask(key, geo.matIx(), geo.mass(), temps, halo)));
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
                neighbor(level, dim, k.cx() - 1, k.sectionY(), k.cz(), lut, mats),
                neighbor(level, dim, k.cx() + 1, k.sectionY(), k.cz(), lut, mats),
                neighbor(level, dim, k.cx(), k.sectionY() - 1, k.cz(), lut, mats),
                neighbor(level, dim, k.cx(), k.sectionY() + 1, k.cz(), lut, mats),
                neighbor(level, dim, k.cx(), k.sectionY(), k.cz() - 1, lut, mats),
                neighbor(level, dim, k.cx(), k.sectionY(), k.cz() + 1, lut, mats));
    }

    /** A neighbour's (temperature, matIx) or null (void) when not loaded. */
    private HaloAssembler.Neighbor neighbor(ServerLevel level, Identifier dim,
                                            int cx, int sectionY, int cz,
                                            MaterialLut lut, ActiveMaterials.State mats) {
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
        // TODO(perf, §8 follow-on): assembles a full 4096-cell geometry per neighbour but only one 256-cell face is used by the halo. A GeometryAssembler.assembleFace(cells, lut, face) variant would cut this 16x.
        GeometryAssembler.Geometry geo = GeometryAssembler.assemble(cellMat, lut);
        // Neighbour mass: stored masses when the section exists, else the geometry default mass per cell.
        float[] mass = (store != null && store.hasSection(key))
                ? store.get(key).massArray().clone()
                : geo.mass();
        return new HaloAssembler.Neighbor(temps, geo.matIx(), mass);
    }
}
