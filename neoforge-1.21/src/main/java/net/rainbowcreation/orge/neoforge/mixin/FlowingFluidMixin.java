package net.rainbowcreation.orge.neoforge.mixin;

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
 * NeoForge mixin that lets ORGE be the sole authority over the water/lava it simulates by cancelling
 * vanilla's scheduled flow/spread tick for ORGE-managed cells (DESIGN &sect;10 Decision 8).
 *
 * <p>Identical contract to the Fabric mixin. Target verified against the 1.21.11 Mojang-mapped
 * sources: {@code FlowingFluid#tick(ServerLevel, BlockPos, BlockState, FluidState)} (note
 * {@code BlockState} precedes {@code FluidState}). Injects at {@code HEAD} with a cancellable
 * callback and delegates to the loader-free {@link OrgeFluidSuppressionBridge} &rarr;
 * {@code OrgeFluidPolicy}.</p>
 *
 * <p>This mixin cancels only the scheduled <b>flow/spread</b> tick for managed cells. It does
 * <b>not</b> stop vanilla lava solidification: lava&harr;water &rarr; obsidian/cobblestone and
 * lava&harr;blue-ice &rarr; basalt are decided in {@code LiquidBlock#shouldSpreadLiquid}
 * (a {@code onPlace}/{@code neighborChanged} side effect), not in {@code Fluid#tick}. Disabling that
 * solidification is the job of the separate {@code LiquidBlockMixin} (global, via
 * {@code OrgeFluidPolicy#allowVanillaLavaSolidification()}). Unmanaged regions are untouched here.
 * Server-side only.</p>
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
