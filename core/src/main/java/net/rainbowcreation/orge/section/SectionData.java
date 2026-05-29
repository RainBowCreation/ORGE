package net.rainbowcreation.orge.section;

/**
 * Per-cell thermal metadata for one section (DESIGN.md §5).
 *
 * <p>Each cell stores <b>only</b> {@code temperature} (K) and {@code mass} (kg);
 * material identity is derived from the block via {@code MaterialBindings}, never
 * stored here. In memory this is two parallel {@code float[4096]} arrays. {@code mass}
 * is treated as fluid level from day one (1000 kg ≈ a full 1 m³ water block) so the
 * Phase-2 fluid pass needs no storage rework.</p>
 *
 * <p>On disk a section serializes as {@code UNIFORM} (one T + one mass — the common
 * case far from any heat source) or {@code FULL} (compressed 4096-arrays once a
 * gradient forms). See {@link RegionStore}.</p>
 */
public final class SectionData {

    /** 16 × 16 × 16. */
    public static final int CELLS = 4096;

    /** Fallback ambient temperature when a biome value is unavailable (DESIGN.md §5). */
    public static final float DEFAULT_AMBIENT_K = 285.0f;

    public enum Form {
        /** One temperature + one mass for the whole section. */
        UNIFORM,
        /** Full per-cell arrays. */
        FULL
    }

    private Form form;
    private float uniformTemperature;
    private float uniformMass;
    private float[] temperature; // null while UNIFORM
    private float[] mass;        // null while UNIFORM

    private SectionData(Form form, float uniformTemperature, float uniformMass,
                        float[] temperature, float[] mass) {
        this.form = form;
        this.uniformTemperature = uniformTemperature;
        this.uniformMass = uniformMass;
        this.temperature = temperature;
        this.mass = mass;
    }

    /** A never-simulated section: implicitly uniform ambient T and material default mass. */
    public static SectionData uniform(float temperatureK, float massKg) {
        return new SectionData(Form.UNIFORM, temperatureK, massKg, null, null);
    }

    public Form form() {
        return form;
    }

    /** Temperature of cell {@code i} (0..4095), expanding from UNIFORM transparently. */
    public float temperatureAt(int i) {
        return form == Form.UNIFORM ? uniformTemperature : temperature[i];
    }

    public float massAt(int i) {
        return form == Form.UNIFORM ? uniformMass : mass[i];
    }

    // TODO(phase: section-store): promote UNIFORM -> FULL on first per-cell write,
    //  demote FULL -> UNIFORM when all cells are equal again, and expose the raw
    //  float[] views the engine FFI and scheduler need.
}
