package net.rainbowcreation.orge.scheduler;

/**
 * Pure mapping from a Minecraft biome base temperature (snowy≈0.0, temperate≈0.8,
 * desert≈2.0) to Kelvin for seeding never-simulated cells (DESIGN §5, engine-audit B).
 * Anchored so temperate (0.8) yields exactly {@code SectionData.DEFAULT_AMBIENT_K} (285 K).
 */
public final class BiomeTemperature {

    private BiomeTemperature() {}

    /** {@code 285 + (base - 0.8) * 20}, clamped to the engine's [0, 6000] K range. */
    public static float toKelvin(float biomeBase) {
        float k = 285f + (biomeBase - 0.8f) * 20f;
        if (k < 0f) return 0f;
        if (k > 6000f) return 6000f;
        return k;
    }
}
