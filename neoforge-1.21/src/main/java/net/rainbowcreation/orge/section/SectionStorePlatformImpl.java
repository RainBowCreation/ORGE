package net.rainbowcreation.orge.section;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * NeoForge implementation of {@link SectionStorePlatform} (matched by {@code <name>Impl}).
 *
 * <p>Subscribes {@code ChunkEvent.Load} / {@code ChunkEvent.Unload} on the game event
 * bus ({@code NeoForge.EVENT_BUS}). These fire on both client and server levels, so we
 * guard {@code getLevel() instanceof ServerLevel} — ORGE state is server-authoritative.
 * The dimension key is {@code serverLevel.dimension().identifier()}.</p>
 */
public final class SectionStorePlatformImpl {

    private SectionStorePlatformImpl() {
    }

    public static void registerChunkHooks(SectionStoreManager manager) {
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                ChunkPos pos = event.getChunk().getPos();
                manager.onChunkLoad(level.dimension().identifier(), pos.x, pos.z);
            }
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                ChunkPos pos = event.getChunk().getPos();
                manager.onChunkUnload(level.dimension().identifier(), pos.x, pos.z);
            }
        });
    }
}
