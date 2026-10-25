package net.rainbowcreation.orge.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.block.ThirstyFarmland;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge mixin for thirsty farmland (issue #35). {@code FarmBlock#randomTick} is vanilla farmland's only
 * hydration point: it keeps the block moist while {@code isNearWater} finds water in its 9×2×9 box or it rains
 * above it. We inject at {@code HEAD} (same world state vanilla is about to read) and hand the tick to
 * {@link ThirstyFarmland#onRandomTick}, which queues a drain of the water block vanilla's search finds
 * (none when it rains); vanilla then runs unchanged. Server-side effect only — random ticks run on the
 * {@code ServerLevel}, and nothing new reaches a client. Target verified against 1.21.11 mojmap
 * ({@code protected void randomTick(BlockState, ServerLevel, BlockPos, RandomSource)}).
 */
@Mixin(FarmBlock.class)
public abstract class FarmBlockMixin {

    @Inject(method = "randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"))
    private void orge$thirstyFarmland(BlockState state, ServerLevel level, BlockPos pos,
                                      RandomSource random, CallbackInfo ci) {
        ThirstyFarmland.onRandomTick(level, pos);
    }
}
