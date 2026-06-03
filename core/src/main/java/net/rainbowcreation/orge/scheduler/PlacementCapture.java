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
        capture(queue, dim, cx, cz, cell, live, incumbent, biomeAmbientK, false);
    }

    /**
     * Same as {@link #capture(PendingInjections, Identifier, int, int, int, Material, Material, float)},
     * but when {@code pendingRemoval} is {@code true} the cell has a same-window BREAK removal already
     * queued, so the recorded {@code incumbent} (last-cycle engine output, which still names the broken
     * species) is STALE. Treat the incumbent as absent: a re-place of the SAME species is then captured as
     * a placement-into-vacuum (rather than dropped as a self-write), and its enqueue supersedes the
     * removal — so the engine sees break→vacuum→inject and the durable identity stays in sync with the
     * engine cell. A {@code null} live material (non-ORGE block) is still not captured.
     */
    public static void capture(PendingInjections queue, Identifier dim, int cx, int cz, int cell,
                               Material live, Material incumbent, float biomeAmbientK,
                               boolean pendingRemoval) {
        Material effectiveIncumbent = pendingRemoval ? null : incumbent;
        if (!PlacementInjectionPolicy.isDisplacement(live, effectiveIncumbent)) {
            return;
        }
        float temp = live.hasDefaultTemperature() ? live.defaultTemperature() : biomeAmbientK;
        queue.enqueue(dim, cx, cz, cell, live.id(), live.defaultMass(), temp);
    }
}
