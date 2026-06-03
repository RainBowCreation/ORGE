package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;

/**
 * Pure decision for the placement-injection capture (spec B1/B5): a block change is a displace-and-
 * inject placement iff a movable new species replaced a DIFFERENT movable incumbent. Everything else
 * keeps the existing reseed/seed path:
 * <ul>
 *   <li>same species (reconciler self-write, engine-output repaint) → not an injection;</li>
 *   <li>non-movable incumbent (stone/ice/void over which a fluid was set) → no fluid mass to
 *       displace, existing {@code ColumnAssembler} seed handles it;</li>
 *   <li>non-movable new species (placing a solid) → not a fluid placement;</li>
 *   <li>unknown incumbent (untracked cell, {@code null}) → nothing known to displace.</li>
 * </ul>
 */
public final class PlacementInjectionPolicy {

    private PlacementInjectionPolicy() {
    }

    /**
     * @param live      material of the newly-placed block at the cell (from the live world).
     * @param incumbent material of the cell's recorded engine-output species (the thing to displace),
     *                  or {@code null} if the cell is untracked.
     */
    public static boolean isDisplacement(Material live, Material incumbent) {
        return live != null && incumbent != null
                && live.movable() && incumbent.movable()
                && !live.id().equals(incumbent.id());
    }
}
