package net.rainbowcreation.orge.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * NeoForge mixin that lets ORGE be the sole authority over the water/lava it simulates by cancelling
 * vanilla's scheduled flow/spread tick for ORGE-managed cells (DESIGN &sect;10 Decision 8).
 *
 * <p>Identical contract to the Fabric mixin. Target verified against the 1.21.11 Mojang-mapped
 * sources: {@code FlowingFluid#tick(ServerLevel, BlockPos, BlockState, FluidState)} (note
 * {@code BlockState} precedes {@code FluidState}). Injects at {@code HEAD} with a cancellable
 * callback and delegates to the loader-free {@link OrgeFluidSuppressionBridge} &rarr;
 * {@code OrgeFluidPolicy}.</p>
 *
 * <p><b>Obsidian stays reachable.</b> The lava&harr;water solidification (obsidian / cobblestone /
 * basalt) is driven by {@code LiquidBlock#shouldSpreadLiquid} via {@code onPlace}/
 * {@code neighborChanged}, NOT by {@code FlowingFluid#tick}; and the policy additionally refuses to
 * suppress whenever an interacting fluid is adjacent. Server-side only.</p>
 */
@Mixin(net.minecraft.world.level.material.FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD"), cancellable = true)
    private void orge$suppressManagedFlow(ServerLevel level, BlockPos pos, BlockState blockState,
                                          FluidState fluidState, CallbackInfo ci) {
        if (OrgeFluidSuppressionBridge.suppressTick(level, pos, fluidState, Fluids.WATER, Fluids.LAVA,
                Direction.values())) {
            ci.cancel();
        }
    }
}
