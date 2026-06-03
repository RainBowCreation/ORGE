package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

/**
 * Pure capture step for placement injection (spec B2/B5). Given the live placed material and the
 * recorded incumbent material at a cell, enqueue a placement intent iff it is a movable→movable
 * displacement ({@link PlacementInjectionPolicy}). The intent carries the NEW species' id +
 * {@code defaultMass} seed + seed temperature (material default, else biome ambient) — the same
 * values {@code ColumnAssembler}/{@code MaterialChangeReseed} would have used, now owned by the engine.
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
}
