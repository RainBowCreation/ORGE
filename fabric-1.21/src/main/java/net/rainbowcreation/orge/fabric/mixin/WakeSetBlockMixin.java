package net.rainbowcreation.orge.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.platform.fabric.WakePlatformImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric wake hook (DESIGN &sect;10 Decision 11, trigger (a)): at the TAIL of a successful server-side
 * {@code Level#setBlock}, wake the owning section so a dormant region near a programmatic block change
 * ({@code /setblock}, piston, dispenser, datapack edit, reconciler write) re-enters the active set.
 *
 * <p>Target verified against the 1.21.11 Mojang-mapped {@code Level} (this repo's mixins are
 * Mojang-mapped, mirroring {@code FlowingFluidMixin}): the canonical internal entry is the 4-arg
 * {@code setBlock(BlockPos, BlockState, int, int)} (pos, state, flags, recursionLeft) returning
 * {@code boolean} &mdash; the 3-arg overload delegates to it, so this captures every {@code setBlock}.
 * Server-side only ({@code this instanceof ServerLevel}); fires only when the set succeeded
 * (the return value is {@code true}). Over-waking is harmless (one extra settle step); under-waking the
 * programmatic-edit path is the failure mode.</p>
 */
@Mixin(Level.class)
public abstract class WakeSetBlockMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("TAIL"))
    private void orge$wakeOnSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                     CallbackInfoReturnable<Boolean> cir) {
        // DIAGNOSTIC (toggle -Dorge.debug.inject): unconditional probe BEFORE the guards. If this line
        // never appears in-game, the mixin itself is not being applied/fired (stale build / refmap not
        // on the IDE run classpath). If it appears but [fabric-wake] does not, a guard is rejecting.
        if (net.rainbowcreation.orge.scheduler.InjectDebug.on()
                && net.rainbowcreation.orge.scheduler.InjectDebug.throttle("fabric-mixin-head", 500)) {
            net.rainbowcreation.orge.scheduler.InjectDebug.LOG.info(
                    "[fabric-mixin] setBlock TAIL reached: isServerLevel={} ret={} at ({},{},{})",
                    ((Object) this) instanceof ServerLevel, cir.getReturnValue(), pos.getX(), pos.getY(), pos.getZ());
        }
        if (((Object) this) instanceof ServerLevel level && Boolean.TRUE.equals(cir.getReturnValue())) {
            WakePlatformImpl.wake(level.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
