package net.rainbowcreation.orge.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.block.OrgeBlockPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric mixin disabling vanilla gravity blocks. In 1.21.11 {@code FallingBlock#tick} is the single
 * vanilla entry point that turns an unsupported gravity block (sand, gravel, concrete powder,
 * suspicious sand/gravel, anvils, the dragon egg — every {@code FallingBlock} subclass) into a
 * {@code FallingBlockEntity} via {@code FallingBlockEntity.fall}; {@code onPlace}/{@code updateShape}
 * only schedule that tick. We inject at {@code HEAD} and CANCEL the whole tick whenever
 * {@link OrgeBlockPolicy#allowVanillaGravityBlocks()} is off, so no falling entity is ever spawned
 * and the block stays put. Global by design (ORGE will own granular collapse in its engine).
 * Server-side effect only. Target verified against 1.21.11 mojmap
 * ({@code protected void tick(BlockState, ServerLevel, BlockPos, RandomSource)}).
 */
@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void orge$noGravityBlocks(BlockState state, ServerLevel level, BlockPos pos,
                                      RandomSource random, CallbackInfo ci) {
        if (OrgeBlockPolicy.allowVanillaGravityBlocks()) {
            return;
        }
        ci.cancel(); // skip the vanilla fall check — no FallingBlockEntity spawn
    }
}
