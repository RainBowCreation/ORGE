package net.rainbowcreation.orge.block;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

import java.util.function.Predicate;

/**
 * Thirsty farmland (spec 2026-10-25-thirsty-farmland §2): the per-loader mixin on {@code FarmBlock#randomTick}
 * calls {@link #onRandomTick} at {@code HEAD}, before vanilla's own hydration check, then vanilla runs
 * unchanged. A farmland hydrated by water asks the installed {@link Sink} to drain the water block vanilla's
 * search finds; one hydrated by rain drains nothing. Server-side only: nothing here reaches a client.
 */
public final class ThirstyFarmland {

    /** Receives one drain request per hydrating random tick: the source water's world block. */
    @FunctionalInterface
    public interface Sink {
        void request(Identifier dim, int x, int y, int z);
    }

    /** {@code null} until ORGE installs the live queue (inert before, and in headless worlds). */
    private static volatile Sink sink;

    private ThirstyFarmland() {}

    /** Install (or clear, with {@code null}) the drain sink. Called once from {@code Orge.init}. */
    public static void setSink(Sink s) {
        sink = s;
    }

    /** The mixin entry point (server thread). */
    public static void onRandomTick(ServerLevel level, BlockPos pos) {
        if (sink == null) {
            return;
        }
        tick(level.dimension().identifier(), pos, level.isRainingAt(pos.above()),
                p -> level.getFluidState(p).is(FluidTags.WATER));
    }

    /** One random tick of the farmland at {@code pos}: request a drain unless it rains or no water is near. */
    static void tick(Identifier dim, BlockPos pos, boolean raining, Predicate<BlockPos> isWater) {
        Sink s = sink;
        if (s == null || raining) {
            return; // rain hydrates for free — even beside water
        }
        BlockPos source = hydratingWater(pos, isWater);
        if (source != null) {
            s.request(dim, source.getX(), source.getY(), source.getZ());
        }
    }

    /**
     * The water block vanilla's {@code FarmBlock#isNearWater} finds for the farmland at {@code pos}: the first
     * position of {@code betweenClosed(pos + (−4, 0, −4), pos + (4, 1, 4))} (x fastest, then y, then z) whose
     * fluid is water, or {@code null} when none is.
     */
    static BlockPos hydratingWater(BlockPos pos, Predicate<BlockPos> isWater) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (isWater.test(p)) {
                return p.immutable();
            }
        }
        return null;
    }
}
