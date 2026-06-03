package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;

/**
 * Pure decision for the placement-injection capture: a placement is captured (enqueued for
 * displace-and-inject) when the placed material differs from the recorded incumbent species, or when the
 * cell is untracked. Specifically:
 * <ul>
 *   <li>the cell is untracked ({@code incumbent == null}) — enqueue so the placement is made durable
 *       against the stale-write-back vanish race;</li>
 *   <li>OR the placed species id differs from the incumbent species id — the new block replaces whatever
 *       was there (fluid, solid, or anything else).</li>
 * </ul>
 * Not captured: {@code live == null} (no ORGE material for the placed block), or a self-write where
 * {@code live.id()} equals {@code incumbent.id()} (the reconciler's own engine-output repaint).
 *
 * <p>{@code movable()} is NOT consulted here. It governs only whether a substance flows after placement,
 * not whether the placement itself is captured. Both solid and fluid placements over a different recorded
 * incumbent are treated identically: enqueue for displace-and-inject.</p>
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
        if (live == null) return false;
        if (incumbent == null) return true;           // untracked cell — enqueue for durability
        return !live.id().equals(incumbent.id());     // different species → displace-and-inject
    }
}
