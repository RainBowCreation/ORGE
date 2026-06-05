package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

/**
 * Pure capture step for placement injection (spec B2/B5). Given the live placed material and the
 * recorded incumbent material at a cell, enqueue a placement intent iff the cell carries an ORGE
 * material ({@link PlacementInjectionPolicy#shouldInject}). The intent carries the placed species' id +
 * {@code defaultMass} seed + seed temperature (material default, else biome ambient) — the same values
 * {@code ColumnAssembler} would have used, now owned by the engine.
 *
 * <p>A DIFFERENT (or untracked) species is a displace-and-inject; a SAME species is an in-place top-up
 * — both enqueue here. The engine ({@code apply_injections}) short-circuits a same-species injection to
 * top the cell up to the source mass without relocating the incumbent (idempotent when already full),
 * which is what makes "place water on water" and repeated placement onto a tracked cell work.</p>
 */
public final class PlacementCapture {

    private PlacementCapture() {
    }

    public static void capture(PendingInjections queue, Identifier dim, int cx, int cz, int cell,
                               Material live, Material incumbent, float biomeAmbientK) {
        if (!PlacementInjectionPolicy.shouldInject(live, incumbent)) {
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
     * delegates to {@link #capture}: a different species = a genuine displacement, and a same-species place
     * with NO pending removal = an in-place top-up (the engine no-ops when the cell is already full).
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
