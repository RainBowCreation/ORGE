package net.rainbowcreation.orge.section;

import java.util.Arrays;

/**
 * Per-cell thermal metadata for one section (DESIGN.md §5).
 *
 * <p>Each cell stores <b>only</b> {@code temperature} (K) and {@code mass} (kg);
 * material identity is derived from the block via the first-touch
 * {@code BlockMaterialRule}, never stored here. In memory this is two parallel {@code float[4096]} arrays. {@code mass}
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

    // -------------------------------------------------------------------------
    // Factory: full
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@code FULL} section by adopting the two supplied arrays directly
     * (no copy — the codec hands over freshly-read arrays).
     *
     * @param temperature length-{@value CELLS} temperature array (K)
     * @param mass        length-{@value CELLS} mass array (kg)
     * @throws IllegalArgumentException if either array has a length other than {@value CELLS}
     */
    public static SectionData full(float[] temperature, float[] mass) {
        if (temperature.length != CELLS) {
            throw new IllegalArgumentException(
                    "temperature array length must be " + CELLS + ", got " + temperature.length);
        }
        if (mass.length != CELLS) {
            throw new IllegalArgumentException(
                    "mass array length must be " + CELLS + ", got " + mass.length);
        }
        return new SectionData(Form.FULL, 0f, 0f, temperature, mass);
    }

    // -------------------------------------------------------------------------
    // Uniform accessors (for the codec)
    // -------------------------------------------------------------------------

    /**
     * Returns the uniform temperature value. Only meaningful when {@link #form()} is
     * {@link Form#UNIFORM}; a section constructed as {@code FULL} (via {@link #full})
     * returns {@code 0}.
     */
    public float uniformTemperature() {
        return uniformTemperature;
    }

    /**
     * Returns the uniform mass value. Only meaningful when {@link #form()} is
     * {@link Form#UNIFORM}; a section constructed as {@code FULL} (via {@link #full})
     * returns {@code 0}.
     */
    public float uniformMass() {
        return uniformMass;
    }

    // -------------------------------------------------------------------------
    // Promotion: UNIFORM -> FULL on first per-cell write
    // -------------------------------------------------------------------------

    /**
     * Promotes this section from {@code UNIFORM} to {@code FULL}, back-filling every
     * cell with the current uniform values. No-op if already {@code FULL}.
     */
    private void promote() {
        if (form == Form.FULL) {
            return;
        }
        temperature = new float[CELLS];
        mass = new float[CELLS];
        Arrays.fill(temperature, uniformTemperature);
        Arrays.fill(mass, uniformMass);
        form = Form.FULL;
    }

    /**
     * Sets the temperature (K) of cell {@code i}, promoting to {@code FULL} if needed.
     *
     * @param i cell index (0..{@value CELLS}-1)
     * @param v temperature in Kelvin
     */
    public void setTemperature(int i, float v) {
        promote();
        temperature[i] = v;
    }

    /**
     * Sets the mass (kg) of cell {@code i}, promoting to {@code FULL} if needed.
     *
     * @param i cell index (0..{@value CELLS}-1)
     * @param v mass in kg
     */
    public void setMass(int i, float v) {
        promote();
        mass[i] = v;
    }

    // -------------------------------------------------------------------------
    // Raw array views (live — the engine writes directly into these)
    // -------------------------------------------------------------------------

    /**
     * Returns the <em>live</em> temperature array (length {@value CELLS}).
     *
     * <p><b>Contract:</b> the returned reference is the actual backing store —
     * any writes by the caller are immediately visible via {@link #temperatureAt(int)}.
     * If the section is currently {@code UNIFORM} it is force-promoted to {@code FULL}
     * so that the caller always receives a real array.</p>
     */
    public float[] temperatureArray() {
        promote();
        return temperature;
    }

    /**
     * Returns the <em>live</em> mass array (length {@value CELLS}).
     *
     * <p><b>Contract:</b> the returned reference is the actual backing store —
     * any writes by the caller are immediately visible via {@link #massAt(int)}.
     * If the section is currently {@code UNIFORM} it is force-promoted to {@code FULL}
     * so that the caller always receives a real array.</p>
     */
    public float[] massArray() {
        promote();
        return mass;
    }

    // -------------------------------------------------------------------------
    // Demotion: FULL -> UNIFORM when all cells are equal
    // -------------------------------------------------------------------------

    /**
     * Attempts to collapse a {@code FULL} section back to {@code UNIFORM} when every
     * cell holds the same temperature and mass.
     *
     * <p>Uses exact {@code float ==} comparison — demotion only fires when the engine
     * genuinely left all cells identical (e.g. after a full-section reset).</p>
     *
     * @return {@code true} if the section is (or becomes) {@code UNIFORM};
     *         {@code false} if cells differ and the section stays {@code FULL}
     */
    public boolean demoteIfUniform() {
        if (form == Form.UNIFORM) {
            return true;
        }
        float t0 = temperature[0];
        float m0 = mass[0];
        for (int i = 1; i < CELLS; i++) {
            if (temperature[i] != t0 || mass[i] != m0) {
                return false;
            }
        }
        uniformTemperature = t0;
        uniformMass = m0;
        temperature = null;
        mass = null;
        form = Form.UNIFORM;
        return true;
    }

    // -------------------------------------------------------------------------
    // Value equality
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} iff this section and {@code o} have the same temperature
     * and mass in every cell (including the UNIFORM-vs-FULL cross-comparison).
     *
     * <p>Uses {@link Float#compare(float, float)} for exact equality (no epsilon).
     * Does <em>not</em> override {@link Object#equals} — identity semantics are
     * preserved; use this named helper when value equality is required.</p>
     *
     * @param o the other section to compare
     */
    public boolean equalsValue(SectionData o) {
        for (int i = 0; i < CELLS; i++) {
            if (Float.compare(this.temperatureAt(i), o.temperatureAt(i)) != 0) {
                return false;
            }
            if (Float.compare(this.massAt(i), o.massAt(i)) != 0) {
                return false;
            }
        }
        return true;
    }
}
