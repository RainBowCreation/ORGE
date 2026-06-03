package net.rainbowcreation.orge.section;

/**
 * Supplies the implicit ambient state for a section that has never been simulated or
 * stored (DESIGN.md §5): UNIFORM(biome-ambient temperature, material defaultMass).
 *
 * <p>Injected seam (kept pure for unit testing). The real biome-temperature +
 * material-defaultMass backed implementation is wired by the loader integration;
 * tests and early wiring may use {@link #FALLBACK}.</p>
 */
@FunctionalInterface
public interface AmbientProvider {

    /**
     * Biome-derived ambient temperature (K) for this section. Implementations fall back
     * to {@link SectionData#DEFAULT_AMBIENT_K} (~285 K) when no biome value is available.
     */
    float ambientTemperatureK(SubchunkKey key);

    /**
     * Material {@code defaultMass} (kg) for a never-simulated cell in this section.
     * Defaults to {@code 0} — a far/empty (air) section carries ~no mass until simulated.
     */
    default float ambientMassKg(SubchunkKey key) {
        return 0.0f;
    }

    /** Constant fallback: ~285 K, zero mass. */
    AmbientProvider FALLBACK = key -> SectionData.DEFAULT_AMBIENT_K;
}
