package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.function.IntFunction;

/**
 * Per-cell initial temperatures for a never-simulated section (engine-audit B): a cell
 * whose material defines {@code default_temperature} (a source) seeds there; every other
 * cell seeds at the section's biome ambient. Pure — the per-cell material lookup is injected.
 */
public final class AmbientSeeder {

    private AmbientSeeder() {}

    public static float[] seed(IntFunction<Material> cellMaterial, float biomeAmbientK) {
        float[] t = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            t[i] = seedT(cellMaterial.apply(i), biomeAmbientK);
        }
        return t;
    }

    /** One cell's seed: its material's {@code default_temperature} (a source), else the biome ambient
     *  (also for an unresolvable {@code null} material). */
    public static float seedT(Material m, float biomeAmbientK) {
        return m != null && m.hasDefaultTemperature() ? m.defaultTemperature() : biomeAmbientK;
    }
}
