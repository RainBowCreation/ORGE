package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.List;

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
    private MaterialPalette materials; // null until the first per-cell material write
    private float[] velX; // null until first velocity write or array request
    private float[] velY;
    private float[] velZ;
    private float[] p;    // dynamic pressure (Pa-ish gauge, >=0); null until first pressure write/array request
    private float[] swapReady; // §5.3 swap-cadence accumulator (law #7 bookkeeping, dimensionless >=0); null until first write/array request; IN-MEMORY ONLY, NOT serialized

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
    // Velocity channels (independent lazy allocation, default 0)
    // -------------------------------------------------------------------------

    /**
     * Allocates all three velocity arrays (zero-filled by JVM default) if not yet present.
     * Does NOT promote temp/mass — call {@link #promote()} first when that is required.
     */
    private void ensureVelocity() {
        if (velX == null) {
            velX = new float[CELLS];
            velY = new float[CELLS];
            velZ = new float[CELLS];
        }
    }

    /** X-component of cell {@code i}'s velocity (m/s). Returns {@code 0} until first write. */
    public float velXAt(int i) { return velX == null ? 0f : velX[i]; }

    /** Y-component of cell {@code i}'s velocity (m/s). Returns {@code 0} until first write. */
    public float velYAt(int i) { return velY == null ? 0f : velY[i]; }

    /** Z-component of cell {@code i}'s velocity (m/s). Returns {@code 0} until first write. */
    public float velZAt(int i) { return velZ == null ? 0f : velZ[i]; }

    /**
     * Sets the velocity of cell {@code i}, promoting this section to {@code FULL} (so that
     * temp/mass arrays are materialized alongside the velocity channels).
     *
     * @param i  cell index (0..{@value CELLS}-1)
     * @param vx X velocity (m/s)
     * @param vy Y velocity (m/s)
     * @param vz Z velocity (m/s)
     */
    public void setVelocity(int i, float vx, float vy, float vz) {
        promote();
        ensureVelocity();
        velX[i] = vx;
        velY[i] = vy;
        velZ[i] = vz;
    }

    /**
     * Returns the <em>live</em> velX array (length {@value CELLS}), allocating it (and velY/velZ)
     * if needed. Also promotes temp/mass to {@code FULL}.
     */
    public float[] velXArray() {
        promote();
        ensureVelocity();
        return velX;
    }

    /**
     * Returns the <em>live</em> velY array (length {@value CELLS}), allocating it (and velX/velZ)
     * if needed. Also promotes temp/mass to {@code FULL}.
     */
    public float[] velYArray() {
        promote();
        ensureVelocity();
        return velY;
    }

    /**
     * Returns the <em>live</em> velZ array (length {@value CELLS}), allocating it (and velX/velY)
     * if needed. Also promotes temp/mass to {@code FULL}.
     */
    public float[] velZArray() {
        promote();
        ensureVelocity();
        return velZ;
    }

    // -------------------------------------------------------------------------
    // Dynamic-pressure channel (independent lazy allocation, default 0)
    // -------------------------------------------------------------------------

    /**
     * Allocates the single pressure array (zero-filled by JVM default) if not yet present.
     * Independent of velocity (a section may carry p without v, and vice versa).
     */
    private void ensurePressure() {
        if (p == null) {
            p = new float[CELLS];
        }
    }

    /** Dynamic pressure of cell {@code i} (Pa-ish gauge, >=0). Returns {@code 0} until first write. */
    public float pAt(int i) { return p == null ? 0f : p[i]; }

    /**
     * Sets the dynamic pressure of cell {@code i}, promoting this section to {@code FULL} (so that
     * temp/mass arrays are materialized alongside the pressure channel).
     *
     * @param i  cell index (0..{@value CELLS}-1)
     * @param pv dynamic pressure (Pa-ish gauge, >=0)
     */
    public void setPressure(int i, float pv) {
        promote();
        ensurePressure();
        p[i] = pv;
    }

    /**
     * Returns the <em>live</em> pressure array (length {@value CELLS}), allocating it if needed.
     * Also promotes temp/mass to {@code FULL}.
     */
    public float[] pArray() {
        promote();
        ensurePressure();
        return p;
    }

    // -------------------------------------------------------------------------
    // Swap-cadence accumulator channel (§5.3, law #7 bookkeeping)
    // IN-MEMORY ONLY — NEVER serialized; resets to 0 on world reload (spec-acceptable).
    // -------------------------------------------------------------------------

    /**
     * Allocates the single swapReady array (zero-filled by JVM default) if not yet present.
     * Independent of velocity and pressure (a section may carry swapReady without v/p, and vice versa).
     */
    private void ensureSwapReady() {
        if (swapReady == null) {
            swapReady = new float[CELLS];
        }
    }

    /** §5.3 swap-cadence accumulator of cell {@code i} (dimensionless, >=0). Returns {@code 0} until first write. */
    public float swapReadyAt(int i) { return swapReady == null ? 0f : swapReady[i]; }

    /**
     * Sets the swap-cadence accumulator of cell {@code i}, promoting this section to {@code FULL} (so that
     * temp/mass arrays are materialized alongside the swapReady channel).
     *
     * @param i cell index (0..{@value CELLS}-1)
     * @param v swap-cadence accumulator (dimensionless, >=0)
     */
    public void setSwapReady(int i, float v) {
        promote();
        ensureSwapReady();
        swapReady[i] = v;
    }

    /**
     * Returns the <em>live</em> swapReady array (length {@value CELLS}), allocating it if needed.
     * Also promotes temp/mass to {@code FULL}.
     */
    public float[] swapReadyArray() {
        promote();
        ensureSwapReady();
        return swapReady;
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
        // Only demote if velocity is absent or all-zero (X, Y and Z).
        if (velX != null) {
            for (int i = 0; i < CELLS; i++) {
                if (velX[i] != 0f || velY[i] != 0f || velZ[i] != 0f) {
                    return false;
                }
            }
        }
        // Only demote if pressure is absent or all-zero (else demotion would silently drop p).
        if (p != null) {
            for (int i = 0; i < CELLS; i++) {
                if (p[i] != 0f) {
                    return false;
                }
            }
        }
        // Only demote if swapReady is absent or all-zero (else demotion would silently drop a
        // nonzero §5.3 cadence accumulator — law #7 bookkeeping must survive section collapse).
        if (swapReady != null) {
            for (int i = 0; i < CELLS; i++) {
                if (swapReady[i] != 0f) {
                    return false;
                }
            }
        }
        uniformTemperature = t0;
        uniformMass = m0;
        temperature = null;
        mass = null;
        velX = null;
        velY = null;
        velZ = null;
        p = null;
        swapReady = null;
        form = Form.UNIFORM;
        return true;
    }

    // -------------------------------------------------------------------------
    // Material identity layer (durable-material §): per-section palette + char[4096]
    // -------------------------------------------------------------------------

    /** Whether this section has allocated its per-cell material layer (first material write). */
    public boolean hasMaterials() {
        return materials != null;
    }

    /** Whether this section has non-default (non-null) velocity arrays. Used by the codec. */
    public boolean hasVelocity() {
        return velX != null;
    }

    /** Whether this section has a non-default (non-null) pressure array. Used by the codec. */
    public boolean hasPressure() {
        return p != null;
    }

    /** Whether this section has a non-default (non-null) swapReady array (in-memory only, never serialized). */
    public boolean hasSwapReady() {
        return swapReady != null;
    }

    /**
     * The material id of cell {@code i} (0..{@value CELLS}-1). Reads the {@code orge:vacuum}
     * sentinel ({@link MaterialPalette#VACUUM_ID}) for any cell never written — including every
     * cell when no material layer has been allocated.
     */
    public Identifier materialAt(int i) {
        return materials == null ? MaterialPalette.VACUUM_ID : materials.get(i);
    }

    /**
     * Sets the material id of cell {@code i} (0..{@value CELLS}-1), lazily allocating the material
     * layer on first call. Also promotes temp/mass to {@code FULL} so all three per-cell layers stay
     * aligned for the codec.
     */
    public void setMaterialAt(int i, Identifier id) {
        if (materials == null) {
            materials = new MaterialPalette();
        }
        promote();
        materials.set(i, id);
    }

    /**
     * The material palette (size includes the slot-0 vacuum sentinel). When no material layer is
     * allocated, returns a singleton {@code [VACUUM_ID]}.
     */
    public List<Identifier> palette() {
        return materials == null ? List.of(MaterialPalette.VACUUM_ID) : materials.palette();
    }

    /** The live material layer, or {@code null} if none has been allocated. For the codec (D2). */
    public MaterialPalette materials() {
        return materials;
    }

    /** Installs a reconstructed material layer (codec, on load). */
    public void adoptMaterials(MaterialPalette m) {
        this.materials = m;
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
