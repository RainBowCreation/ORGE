package net.rainbowcreation.orge.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.fluid.OrgeFluidPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge mixin closing vanilla's two ice→water paths while {@link OrgeFluidPolicy#allowVanillaIceMelt()} is
 * off. {@code IceBlock#melt} (reached from {@code randomTick} when block light exceeds 11, and from
 * {@code FrostedIceBlock} at its last age) is cancelled at {@code HEAD}. In {@code playerDestroy} only
 * the {@code setBlockAndUpdate(pos, meltsInto())} call is skipped: drops, stats and exhaustion still run,
 * and the broken block stays air (a removal). Both paths skipped the latent heat and seeded the water at
 * its default 290 K; ORGE melts ice by temperature. Targets verified against 1.21.11.
 */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {

    @Inject(method = "melt(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true)
    private void orge$noVanillaMelt(BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
        if (!OrgeFluidPolicy.allowVanillaIceMelt()) {
            ci.cancel();
        }
    }

    @Redirect(method = "playerDestroy",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean orge$noBreakIntoWater(Level level, BlockPos pos, BlockState water) {
        return OrgeFluidPolicy.allowVanillaIceMelt() && level.setBlockAndUpdate(pos, water);
    }
}
