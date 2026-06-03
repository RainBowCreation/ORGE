package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

/**
 * Pure capture step for placement injection (spec B2/B5). Given the live placed material and the
 * recorded incumbent material at a cell, enqueue a placement intent iff it is a movable→movable
 * displacement ({@link PlacementInjectionPolicy}). The intent carries the NEW species' id +
 * {@code defaultMass} seed + seed temperature (material default, else biome ambient) — the same
 * values {@code ColumnAssembler} would have used, now owned by the engine.
 */
public final class PlacementCapture {

    private PlacementCapture() {
    }

    public static void capture(PendingInjections queue, Identifier dim, int cx, int cz, int cell,
                               Material live, Material incumbent, float biomeAmbientK) {
        if (!PlacementInjectionPolicy.isDisplacement(live, incumbent)) {
            return;
        }
        float temp = live.hasDefaultTemperature() ? live.defaultTemperature() : biomeAmbientK;
        queue.enqueue(dim, cx, cz, cell, live.id(), live.defaultMass(), temp);
    }

    /**
     * Removal-aware capture entry point used by the live reconciler. When the cell has a same-window
     * removal pending AND the placed species equals the recorded incumbent (a re-place of the SAME thing —
     * a player break+replace, OR a fluid re-asserting over a transient air-level edit that the air-override
     * turned into a removal), the removal is STALE: cancel it and enqueue nothing. Cancelling — rather than
     * letting the lone removal stand or force-injecting the species — is the fix for BOTH failure modes:
     * <ul>
     *   <li>letting the removal stand stomps a solid's engine cell to vacuum under a still-solid durable
     *       identity → neighbours flow THROUGH the phantom hole;</li>
     *   <li>force-injecting the placement (a fresh {@code defaultMass}) FABRICATES mass for a movable fluid
     *       every time it re-asserts over a transient removal (the mass-doubling regression).</li>
     * </ul>
     * The cancelled cell keeps its durable identity + stored mass; no injection is emitted. Any OTHER case
     * (different species = a genuine displacement, or no pending removal) delegates unchanged to
     * {@link #capture}.
     */
    public static void captureOrCancelStaleRemoval(PendingInjections queue, Identifier dim, int cx, int cz,
                                                   int cell, Material live, Material incumbent,
                                                   float biomeAmbientK) {
        if (live != null && incumbent != null && live.id().equals(incumbent.id())
                && queue.hasPendingRemoval(dim, cx, cz, cell)) {
            queue.cancelRemoval(dim, cx, cz, cell);
            return;
        }
        capture(queue, dim, cx, cz, cell, live, incumbent, biomeAmbientK);
    }
}
