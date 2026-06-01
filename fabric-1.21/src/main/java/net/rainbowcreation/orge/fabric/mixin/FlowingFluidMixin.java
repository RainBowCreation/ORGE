package net.rainbowcreation.orge.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.rainbowcreation.orge.fluid.OrgeFluidSuppressionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric mixin that lets ORGE be the sole authority over the water/lava it simulates by cancelling
 * vanilla's scheduled flow/spread tick for ORGE-managed cells (DESIGN &sect;10 Decision 8).
 *
 * <p>Target verified against the 1.21.11 Mojang-mapped sources: the scheduled-tick entry is
 * {@code FlowingFluid#tick(ServerLevel, BlockPos, BlockState, FluidState)} (note the param order:
 * {@code BlockState} precedes {@code FluidState}). We inject at {@code HEAD} with a cancellable
 * callback and delegate the decision to the loader-free {@link OrgeFluidSuppressionBridge} (which
 * calls the pure {@code OrgeFluidPolicy}).</p>
 *
 * <p><b>Obsidian intentionally disabled for managed fluids.</b> ORGE is now authoritative over the
 * water/lava it simulates, so this tick is cancelled for managed cells unconditionally (no
 * adjacent-interacting-fluid carve-out). Vanilla lava&harr;water solidification (obsidian /
 * cobblestone / basalt) therefore no longer fires inside managed sections &mdash; lava cools to
 * stone thermally via ORGE's phase system, and a future lava-cooling branch reintroduces obsidian.
 * Unmanaged regions are untouched. Server-side only (this tick already runs on
 * {@link ServerLevel}).</p>
 */
@Mixin(net.minecraft.world.level.material.FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD"), cancellable = true)
    private void orge$suppressManagedFlow(ServerLevel level, BlockPos pos, BlockState blockState,
                                          FluidState fluidState, CallbackInfo ci) {
        if (OrgeFluidSuppressionBridge.suppressTick(level, pos, fluidState, Fluids.WATER, Fluids.LAVA)) {
            ci.cancel();
        }
    }
}
