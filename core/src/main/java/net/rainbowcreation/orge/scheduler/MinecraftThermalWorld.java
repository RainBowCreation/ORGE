package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialBindings;
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

    /** Live tag-membership bridge — §6's deferred {@link MaterialBindings.TagMembership} consumer. */
    private static final MaterialBindings.TagMembership LIVE_TAGS = (tagId, blockId) -> {
        // NOTE: getValue returns the default (air) for an unregistered id rather than null.
        // Safe here because every blockId originates from BuiltInRegistries.BLOCK.getKey(block)
        // in materialFor; callers must only pass registered block ids.
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
        return BuiltInRegistries.BLOCK.wrapAsHolder(BuiltInRegistries.BLOCK.getValue(blockId)).is(tag);
    };

    private Material materialFor(Block block, ActiveMaterials.State mats) {
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        Identifier matId = mats.bindings().materialFor(blockId, LIVE_TAGS);
        return mats.registry().getOrFallback(matId);
    }

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
                LevelChunk chunk = loadedChunk(level, key.cx(), key.cz());
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection section = sectionOrNull(chunk, key.sectionY());
                if (section == null) {
                    continue;
                }
                GeometryAssembler.Geometry geo = GeometryAssembler.assemble(
                        i -> materialFor(blockAt(section, i), mats), lut);
                SectionStore store = stores.store(dim);
                float[] temps = (store != null)
                        ? store.get(key).temperatureArray().clone()
                        : ambientTemps();
                NeighborHalo halo = buildHalo(level, dim, key, lut, mats);
                entries.add(new BatchEntry(dim, key,
                        new StepTask(key, geo.matIx(), geo.mass(), temps, halo)));
            }
        }
        return new Batch(entries, lut.materials());
    }

    // Server thread only.
    @Override
    public void writeBack(BatchEntry entry, float[] newTemperatures) {
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(entry.key().cx(), entry.key().cz())) {
            return;
        }
        SectionData data = store.get(entry.key());
        // TODO(perf, §8 follow-on): temperatureArray() force-promotes a UNIFORM ambient section to FULL (two 4096 arrays + fill) right before we overwrite every cell. A SectionData.setAllTemperatures(float[]) that skips the fill would avoid the churn for first-touch sections.
        float[] dst = data.temperatureArray();
        System.arraycopy(newTemperatures, 0, dst, 0, SectionData.CELLS);
        store.put(entry.key(), data);
    }

    private static float[] ambientTemps() {
        float[] t = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, SectionData.DEFAULT_AMBIENT_K);
        return t;
    }

    /** The block at section-local cell index i (x-fastest, x+16y+256z); 0..15 per axis. */
    private static Block blockAt(LevelChunkSection section, int i) {
        int x = i & 15;
        int y = (i >> 4) & 15;
        int z = (i >> 8) & 15;
        return section.getBlockState(x, y, z).getBlock();
    }

    /** A loaded chunk, or null if not currently loaded (never forces generation). */
    private static LevelChunk loadedChunk(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz);
    }

    /** The chunk's section at vanilla sectionY, or null if out of the chunk's Y range. */
    private static LevelChunkSection sectionOrNull(LevelChunk chunk, int sectionY) {
        int idx = chunk.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= chunk.getSectionsCount()) {
            return null;
        }
        return chunk.getSection(idx);
    }

    private void addForcedSections(ServerLevel level, Set<SubchunkKey> union) {
        for (long packed : level.getForceLoadedChunks().toLongArray()) {
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            LevelChunk chunk = loadedChunk(level, cx, cz);
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
        LevelChunk chunk = loadedChunk(level, cx, cz);
        if (chunk == null) return null;
        LevelChunkSection section = sectionOrNull(chunk, sectionY);
        if (section == null) return null;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        SectionStore store = stores.store(dim);
        float[] temps = (store != null) ? store.get(key).temperatureArray().clone() : ambientTemps();
        // TODO(perf, §8 follow-on): assembles a full 4096-cell geometry per neighbour but only one 256-cell face is used by the halo. A GeometryAssembler.assembleFace(cells, lut, face) variant would cut this 16x.
        GeometryAssembler.Geometry geo =
                GeometryAssembler.assemble(i -> materialFor(blockAt(section, i), mats), lut);
        return new HaloAssembler.Neighbor(temps, geo.matIx());
    }
}
