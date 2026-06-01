package net.rainbowcreation.orge.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Loader-free, server-side fact-gatherer that bridges the per-loader {@code FlowingFluid#tick} mixins
 * to the pure {@link OrgeFluidPolicy}. It is allowed to touch vanilla Minecraft types (only
 * loader-specific Fabric/NeoForge APIs are barred from {@code core}); both loaders' mixins call this
 * single method so the MC-side logic lives in one place and the mixins stay razor-thin.
 *
 * <p>It computes the two booleans the policy needs &mdash; is this water/lava? is the position's
 * subchunk an ORGE-managed loaded section? &mdash; and returns the policy's verdict. ORGE is now
 * authoritative over managed fluids, so there is no longer an adjacent-interacting-fluid carve-out:
 * managed water/lava is suppressed even where it would otherwise solidify (see {@link OrgeFluidPolicy}).</p>
 */
public final class OrgeFluidSuppressionBridge {

    private OrgeFluidSuppressionBridge() {}

    /**
     * @param level     the server level the fluid is ticking in
     * @param pos       the ticking fluid's position
     * @param fluidState the ticking fluid state
     * @param water     the vanilla water fluid ({@code Fluids.WATER})
     * @param lava      the vanilla lava fluid ({@code Fluids.LAVA})
     * @return {@code true} iff the mixin should cancel this vanilla tick
     */
    public static boolean suppressTick(ServerLevel level, BlockPos pos, FluidState fluidState,
                                       Fluid water, Fluid lava) {
        Fluid type = fluidState.getType();
        boolean isWater = type.isSame(water);
        boolean isLava = type.isSame(lava);
        if (!isWater && !isLava) {
            return false; // not a fluid ORGE manages — let the policy short-circuit too
        }

        String dimKey = level.dimension().identifier().toString();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int sectionY = Math.floorDiv(pos.getY(), 16);
        boolean managed = OrgeFluidPolicy.isSectionManaged(dimKey, cx, sectionY, cz);

        return OrgeFluidPolicy.shouldSuppressFlow(true, managed);
    }
}
