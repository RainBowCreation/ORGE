package net.rainbowcreation.orge.platform.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.rainbowcreation.orge.scheduler.WakeSink;

/**
 * NeoForge implementation of {@code WakePlatform} (matched by {@code <name>Impl}). Subscribes
 * {@code BlockEvent.NeighborNotifyEvent} on the game event bus ({@code NeoForge.EVENT_BUS}, the same
 * bus {@code SectionStorePlatformImpl} uses for {@code ChunkEvent}): it fires for the neighbour-update
 * fan-out of {@code Level.setBlock}, covering {@code /setblock}, pistons, dispensers, and programmatic
 * edits. Server-guarded ({@code getLevel() instanceof ServerLevel}); ORGE is server-authoritative.
 *
 * <p>Verified against {@code neoforge-21.11.42}: {@code BlockEvent.NeighborNotifyEvent} lives in
 * {@code net.neoforged.neoforge.event.level}; {@code getLevel()} returns a {@code LevelAccessor} and
 * {@code getPos()} a {@code BlockPos} (both inherited from {@code BlockEvent}). Over-waking (a spurious
 * wake) is harmless &mdash; one extra settle step; under-waking is the failure mode.</p>
 */
public final class WakePlatformImpl {

    private WakePlatformImpl() {
    }

    public static void registerBlockChangeWake(WakeSink sink) {
        NeoForge.EVENT_BUS.addListener(BlockEvent.NeighborNotifyEvent.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                BlockPos pos = event.getPos();
                sink.wakeBlock(level.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
            }
        });
    }
}
