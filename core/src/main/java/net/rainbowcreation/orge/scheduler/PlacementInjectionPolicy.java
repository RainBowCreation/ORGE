package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;

/**
 * Pure decision for the placement-injection capture (spec B1/B5): a movable fluid placement becomes a
 * durable displace-and-inject. A placement is captured when:
 * <ul>
 *   <li>the placed (live) material is movable (a fluid), AND</li>
 *   <li>EITHER the cell is untracked ({@code incumbent == null}) — bug 1: an engine never recorded this
 *       cell yet, so we still enqueue to make the placement durable against the stale-write-back vanish
 *       race (the drain treats the unknown incumbent as void; the engine simply places the fluid);</li>
 *   <li>OR there is a recorded, movable incumbent of a DIFFERENT species to displace.</li>
 * </ul>
 * Not captured: a non-movable (solid) placement, a self-write (live == incumbent, the reconciler's own
 * engine-output repaint), or a movable-over-a-recorded-non-movable-incumbent (the existing seed path).
 *
 * <p>NOTE (unified-substance direction): the {@code live.movable()} requirement here and the
 * {@code m.movable()} seed gate in {@code ColumnAssembler} are what currently exclude SOLID placements
 * from the displace-and-inject + default-mass-seed path. Generalising "every block is a substance"
 * (solid placement also displaces + seeds its defaultMass) is the pending bug 2 / bug 3 change.</p>
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
        if (live == null || !live.movable()) {
            return false;
        }
        if (incumbent == null) {
            return true; // bug 1: untracked cell — enqueue anyway so the placement is durable
        }
        return incumbent.movable() && !live.id().equals(incumbent.id());
    }
}
