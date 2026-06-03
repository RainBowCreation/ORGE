package net.rainbowcreation.orge.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import net.rainbowcreation.orge.fluid.OrgeFluidPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge mixin closing the SECOND vanilla lava-solidification path (the first,
 * {@code LiquidBlock#shouldSpreadLiquid} → obsidian/cobblestone/basalt, is handled by
 * {@link LiquidBlockMixin}). In 1.21.11 {@code LavaFluid#spreadTo} independently turns a lava cell
 * that spreads <b>DOWN into water</b> into {@code Blocks.STONE} (plus {@code fizz}) — this is the
 * "new stone block generated between lava and water" the user saw, and it is NOT inside
 * {@code Fluid#tick} (so the managed-only {@link FlowingFluidMixin} tick-suppression does not cover
 * the unmanaged / not-yet-adopted window).
 *
 * <p>We inject at {@code HEAD} and CANCEL {@code spreadTo} only for the solidifying case
 * ({@code direction == DOWN} and the cell below is water) whenever
 * {@link OrgeFluidPolicy#allowVanillaLavaSolidification()} is off — so no stone is ever produced.
 * Every other {@code spreadTo} call (normal lava spread) is untouched. Global by design, matching the
 * "disable it for now" policy already applied to {@link LiquidBlockMixin}; ORGE owns lava cooling
 * (mass → stone by temperature via the phase system), so a future lava-cooling branch flips the
 * policy back on. Server-side effect only. Target verified against 1.21.11 (NeoForge keeps this
 * branch, only wrapping the {@code setBlock} in {@code fireFluidPlaceBlockEvent}).</p>
 */
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {

    @Inject(method = "spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD"), cancellable = true)
    private void orge$noLavaWaterStone(LevelAccessor level, BlockPos pos, BlockState blockState,
                                       Direction direction, FluidState fluidState, CallbackInfo ci) {
        if (OrgeFluidPolicy.allowVanillaLavaSolidification()) {
            return;
        }
        if (direction == Direction.DOWN && level.getFluidState(pos).is(FluidTags.WATER)) {
            ci.cancel(); // skip the vanilla lava→water STONE solidify branch
        }
    }
}
