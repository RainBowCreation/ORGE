package net.rainbowcreation.orge.section;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.world.level.ChunkPos;

/**
 * Fabric implementation of {@link SectionStorePlatform} (matched by {@code <name>Impl}).
 *
 * <p>Registers {@code ServerChunkEvents.CHUNK_LOAD} / {@code CHUNK_UNLOAD}; both
 * callbacks already deliver a {@code ServerLevel}, so these fire server-side only.
 * The dimension key is {@code level.dimension().identifier()}.</p>
 */
public final class SectionStorePlatformImpl {

    private SectionStorePlatformImpl() {
    }

    public static void registerChunkHooks(SectionStoreManager manager) {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            ChunkPos pos = chunk.getPos();
            manager.onChunkLoad(level.dimension().identifier(), pos.x, pos.z);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            ChunkPos pos = chunk.getPos();
            manager.onChunkUnload(level.dimension().identifier(), pos.x, pos.z);
        });
    }
}
