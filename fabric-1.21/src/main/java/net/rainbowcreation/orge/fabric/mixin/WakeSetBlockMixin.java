package net.rainbowcreation.orge.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.rainbowcreation.orge.platform.fabric.WakePlatformImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric wake hook (DESIGN &sect;10 Decision 11, trigger (a)): wake the owning section at the exact
 * cell whose block just changed, so a dormant region near any block edit re-enters the active set.
 *
 * <p><b>Why {@code LevelChunk.setBlockState}, not {@code Level.setBlock}:</b> in-game tracing
 * (2026-06-03) proved the earlier {@code Level#setBlock(BlockPos,BlockState,int,int)} TAIL mixin
 * fires <b>only client-side</b> on Fabric &mdash; never on the integrated-server thread &mdash; so it
 * never woke the authoritative server cell, and player-placed fluid silently vanished. (The mixin,
 * its refmap, and its target were all verified correct; the server simply does not route these writes
 * through the mixed {@code Level} overload in this build.) {@code LevelChunk#setBlockState} is the
 * universal chokepoint every block write funnels through, fires on the server thread, and gives us the
 * precise changed {@code pos} &mdash; matching NeoForge's working {@code NeighborNotifyEvent} (which
 * fires at the actual changed block, not the clicked block the common {@code FILL_BUCKET}/{@code PLACE}
 * events report).</p>
 *
 * <p>Server-guarded ({@code getLevel() instanceof ServerLevel}); ORGE is server-authoritative. Fires
 * only on a real change ({@code setBlockState} returns the previous state, or {@code null} for a
 * same-state no-op). Over-waking is harmless (one extra settle step); under-waking is the failure
 * mode, so this covers /setblock, pistons, dispensers, bucket empty/fill, and the reconciler's own
 * air&harr;fluid repaints (the capture's steady-state filter drops ORGE's own writes).</p>
 */
@Mixin(LevelChunk.class)
public abstract class WakeSetBlockMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void orge$wakeOnChunkSet(BlockPos pos, BlockState state, int flags,
                                     CallbackInfoReturnable<BlockState> cir) {
        // setBlockState returns the previous state on a real change, or null for a same-state no-op.
        if (cir.getReturnValue() == null) {
            return;
        }
        Level level = ((LevelChunk) (Object) this).getLevel();
        boolean orge$isServer = level instanceof ServerLevel;
        // DIAGNOSTIC (toggle -Dorge.debug.inject): unconditional, split per side so the client firing
        // cannot mask the server one. Confirms this chokepoint fires on the Server thread (the
        // Level#setBlock mixin did not). Strip once the Fabric wake is confirmed in-game.
        if (net.rainbowcreation.orge.scheduler.InjectDebug.on()
                && net.rainbowcreation.orge.scheduler.InjectDebug.throttle("fabric-chunkset-" + orge$isServer, 500)) {
            net.rainbowcreation.orge.scheduler.InjectDebug.LOG.info(
                    "[fabric-chunkset] LevelChunk.setBlockState RETURN isServer={} thread={} at ({},{},{})",
                    orge$isServer, Thread.currentThread().getName(), pos.getX(), pos.getY(), pos.getZ());
        }
        if (level instanceof ServerLevel serverLevel) {
            WakePlatformImpl.wake(serverLevel.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
