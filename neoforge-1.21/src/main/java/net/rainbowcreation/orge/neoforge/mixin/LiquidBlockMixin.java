package net.rainbowcreation.orge.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.fluid.OrgeFluidPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge mixin that globally disables vanilla lava solidification on fluid contact so ORGE owns all
 * lava cooling.
 *
 * <p>The vanilla lava&harr;water (obsidian / cobblestone) and lava&harr;blue-ice (basalt &mdash; the
 * "lava-ice" interaction) transitions do <b>not</b> live in {@code Fluid#tick}; they are side effects
 * of {@code LiquidBlock#shouldSpreadLiquid(Level, BlockPos, BlockState)}, invoked from
 * {@code onPlace}/{@code neighborChanged}. That method sets the solid block, calls {@code fizz}, and
 * returns {@code false} to stop spreading; otherwise it returns {@code true} so the caller schedules a
 * normal spread tick. Target verified with {@code javap} against the 1.21.11 Mojang-mapped
 * {@code LiquidBlock} (descriptor {@code (Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z};
 * the body references {@code Blocks.OBSIDIAN}/{@code COBBLESTONE}/{@code BASALT}, {@code Blocks.BLUE_ICE}
 * and {@code FluidTags.LAVA}/{@code WATER}).</p>
 *
 * <p>We inject at {@code HEAD} and force the return value to {@code true} (the non-solidifying branch)
 * whenever {@link OrgeFluidPolicy#allowVanillaLavaSolidification()} is off &mdash; skipping the whole
 * body, so no obsidian / cobblestone / basalt is ever produced, while the caller still schedules
 * normal flow. Global (un-gated) by design, matching the user's "disable it for now" ask and closing
 * the managed/unmanaged async window; a future lava-cooling branch flips the policy back on. The pure
 * {@link OrgeFluidPolicy} keeps the decision headlessly testable. Server-side effect only (block edits
 * only run server-side).</p>
 */
@Mixin(net.minecraft.world.level.block.LiquidBlock.class)
public abstract class LiquidBlockMixin {

    @Inject(method = "shouldSpreadLiquid(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true)
    private void orge$disableLavaSolidification(Level level, BlockPos pos, BlockState state,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (!OrgeFluidPolicy.allowVanillaLavaSolidification()) {
            cir.setReturnValue(true);
        }
    }
}
