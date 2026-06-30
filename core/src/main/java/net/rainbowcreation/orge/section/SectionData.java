package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.List;

/**
 * Per-cell EXTENSIVE state for one section (DESIGN.md §5; law §7).
 *
 * <p>Each cell stores only conserved <b>extensive</b> quantities plus its material and pressure:
 * <b>enthalpy {@code E} [J]</b>, <b>momentum {@code (px,py,pz)} [kg·m/s]</b>, {@code mass} [kg],
 * material identity (palette), dynamic pressure {@code P}, and the {@code swapReady} bookkeeping
 * accumulator. The intensive quantities are <b>derived at the boundary, never stored here</b>:
 * temperature {@code T = h⁻¹(E/m)} via the
 * {@link net.rainbowcreation.orge.material.EnthalpyCurve} and velocity {@code v = p/mass}. A stored
 * raw {@code v} or raw {@code T} is the velocity-ghost / temp-ghost drift (law §7) — forbidden.
 * Extensive storage is what makes advection structurally conservative and keeps thinned cells
 * bounded ({@code E→0} and {@code p→0} as {@code mass→0}).</p>
 *
 * <p>In memory the per-cell channels are parallel {@code float[4096]} arrays. On disk a section
 * serializes as {@code UNIFORM} (one E + one mass — the common case far from any heat source) or
 * {@code FULL} (compressed 4096-arrays once a gradient forms). See {@link RegionStore}. The derive
 * to {@code T}/{@code v} (which needs the Material LUT, not held here) lives at every caller
 * boundary, not in this class.</p>
 */
public final class SectionData {

    /** 16 × 16 × 16. */
    public static final int CELLS = 4096;

    /** Fallback ambient temperature when a biome value is unavailable (DESIGN.md §5). */
    public static final float DEFAULT_AMBIENT_K = 285.0f;

    public enum Form {
        /** One enthalpy + one mass for the whole section. */
        UNIFORM,
        /** Full per-cell arrays. */
        FULL
    }

    private Form form;
    private float uniformEnthalpy;
    private float uniformMass;
    private float[] enthalpy; // E [J]; null while UNIFORM
    private float[] mass;     // null while UNIFORM
    private MaterialPalette materials; // null until the first per-cell material write
    private float[] momX; // momentum px [kg·m/s]; null until first momentum write or array request
    private float[] momY; // momentum py
    private float[] momZ; // momentum pz
    private float[] p;    // dynamic pressure (Pa-ish gauge, >=0); null until first pressure write/array request
    private float[] swapReady; // §5.3 swap-cadence accumulator (law #7 bookkeeping, dimensionless >=0); null until first write/array request; IN-MEMORY ONLY, NOT serialized

    // [edit-epoch guard] IN-MEMORY ONLY (not serialized, not in equals). The async scheduler reads a
    // section in the snapshot and writes the engine result back a cycle later. An EXTERNAL edit
    // (/orge set, etc.) made into that window would be clobbered by the stale write-back. So an
    // external edit bumps {@code editEpoch}; the snapshot stamps {@code snapshotEpoch} via
    // {@link #markSnapshot()}; the write-back skips a section where {@link #editedSinceSnapshot()} —
    // the edit survives and is re-snapshotted (and simulated) next cycle. Plain longs, server-thread.
    private long editEpoch = 0L;
    private long snapshotEpoch = 0L;

    private SectionData(Form form, float uniformEnthalpy, float uniformMass,
                        float[] enthalpy, float[] mass) {
        this.form = form;
        this.uniformEnthalpy = uniformEnthalpy;
        this.uniformMass = uniformMass;
        this.enthalpy = enthalpy;
        this.mass = mass;
    }

    /** A never-simulated section: implicitly uniform enthalpy and material default mass. */
    public static SectionData uniform(float enthalpyJ, float massKg) {
        return new SectionData(Form.UNIFORM, enthalpyJ, massKg, null, null);
    }

    public Form form() {
        return form;
    }

    /** Enthalpy E [J] of cell {@code i} (0..4095), expanding from UNIFORM transparently. */
    public float enthalpyAt(int i) {
        return form == Form.UNIFORM ? uniformEnthalpy : enthalpy[i];
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
     * @param enthalpy length-{@value CELLS} enthalpy array (E [J])
     * @param mass     length-{@value CELLS} mass array (kg)
     * @throws IllegalArgumentException if either array has a length other than {@value CELLS}
     */
    public static SectionData full(float[] enthalpy, float[] mass) {
        if (enthalpy.length != CELLS) {
            throw new IllegalArgumentException(
                    "enthalpy array length must be " + CELLS + ", got " + enthalpy.length);
        }
        if (mass.length != CELLS) {
            throw new IllegalArgumentException(
                    "mass array length must be " + CELLS + ", got " + mass.length);
        }
        return new SectionData(Form.FULL, 0f, 0f, enthalpy, mass);
    }

    // -------------------------------------------------------------------------
    // Serialization seam (codec exchanges a SectionSnapshot, not internals)
    // -------------------------------------------------------------------------

    /**
     * Captures this section's current state as an immutable {@link SectionSnapshot} for the codec.
     *
     * <p>Channel presence is encoded by {@code null} components, mirroring this section's lazy
     * allocation exactly: {@code FULL} hands over its live {@code enthalpy}/{@code mass} arrays
     * (a {@code UNIFORM} section reports its two scalars instead), and each optional channel
     * (momentum / pressure / materials) is present iff its backing store has been allocated. The
     * in-memory-only {@code swapReady} channel is never included (law §7). Arrays are aliased, not
     * copied — the codec only reads them.</p>
     */
    SectionSnapshot snapshot() {
        boolean full = (form == Form.FULL);
        List<Identifier> palette = (materials == null) ? null : materials.palette();
        char[] indices = (materials == null) ? null : materials.indices();
        return new SectionSnapshot(
                form,
                uniformEnthalpy,
                uniformMass,
                full ? enthalpy : null,
                full ? mass : null,
                momX, momY, momZ,
                p,
                palette, indices);
    }

    /**
     * Reconstructs a section from a {@link SectionSnapshot} (codec, on load), adopting the snapshot's
     * arrays directly (no copy).
     *
     * <p>Materials are adopted <em>without</em> forcing promotion, so a material layer that rode on a
     * {@code UNIFORM} section (a {@code FULL} section keeps its materials when it later demotes — see
     * {@link #demoteIfUniform()}) round-trips with its form intact. Momentum/pressure imply per-cell
     * arrays, so adopting either promotes to {@code FULL} (a no-op for an already-{@code FULL} snapshot).</p>
     */
    static SectionData fromSnapshot(SectionSnapshot snap) {
        SectionData s = snap.isFull()
                ? full(snap.enthalpy(), snap.mass())
                : uniform(snap.uniformEnthalpy(), snap.uniformMass());
        if (snap.hasMaterials()) {
            s.materials = new MaterialPalette(snap.materialPalette(), snap.materialIndices());
        }
        if (snap.hasMomentum()) {
            s.promote();
            s.momX = snap.momX();
            s.momY = snap.momY();
            s.momZ = snap.momZ();
        }
        if (snap.hasPressure()) {
            s.promote();
            s.p = snap.pressure();
        }
        return s;
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
        enthalpy = new float[CELLS];
        mass = new float[CELLS];
        Arrays.fill(enthalpy, uniformEnthalpy);
        Arrays.fill(mass, uniformMass);
        form = Form.FULL;
    }

    /**
     * Sets the enthalpy E [J] of cell {@code i}, promoting to {@code FULL} if needed.
     *
     * @param i cell index (0..{@value CELLS}-1)
     * @param v enthalpy in Joules
     */
    public void setEnthalpy(int i, float v) {
        promote();
        enthalpy[i] = v;
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
    // Edit-epoch guard (in-memory; protects an external edit from a stale write-back)
    // -------------------------------------------------------------------------

    /** Record an EXTERNAL edit (e.g. {@code /orge set}) — makes this section diverge from any snapshot
     *  already taken, so a stale in-flight write-back skips it instead of clobbering the edit. */
    public void markExternalEdit() {
        editEpoch++;
    }

    /** Stamp the current edit state as "seen by this snapshot" (called when the scheduler reads the
     *  section into a cycle's column batch). */
    public void markSnapshot() {
        snapshotEpoch = editEpoch;
    }

    /** Whether an external edit landed after the last {@link #markSnapshot()} — the write-back of that
     *  snapshot's cycle must NOT persist over this section (its engine input is stale). */
    public boolean editedSinceSnapshot() {
        return editEpoch != snapshotEpoch;
    }

    // -------------------------------------------------------------------------
    // Raw array views (live — the engine writes directly into these)
    // -------------------------------------------------------------------------

    /**
     * Returns the <em>live</em> enthalpy array (length {@value CELLS}).
     *
     * <p><b>Contract:</b> the returned reference is the actual backing store —
     * any writes by the caller are immediately visible via {@link #enthalpyAt(int)}.
     * If the section is currently {@code UNIFORM} it is force-promoted to {@code FULL}
     * so that the caller always receives a real array.</p>
     */
    public float[] enthalpyArray() {
        promote();
        return enthalpy;
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
    // Momentum channels (independent lazy allocation, default 0 = resting)
    // Extensive p [kg·m/s]; velocity v = p/mass is derived at the boundary (law §7).
    // -------------------------------------------------------------------------

    /**
     * Allocates all three momentum arrays (zero-filled by JVM default) if not yet present.
     * Does NOT promote E/mass — call {@link #promote()} first when that is required.
     */
    private void ensureMomentum() {
        if (momX == null) {
            momX = new float[CELLS];
            momY = new float[CELLS];
            momZ = new float[CELLS];
        }
    }

    /** X-component of cell {@code i}'s momentum (kg·m/s). Returns {@code 0} until first write. */
    public float momXAt(int i) { return momX == null ? 0f : momX[i]; }

    /** Y-component of cell {@code i}'s momentum (kg·m/s). Returns {@code 0} until first write. */
    public float momYAt(int i) { return momY == null ? 0f : momY[i]; }

    /** Z-component of cell {@code i}'s momentum (kg·m/s). Returns {@code 0} until first write. */
    public float momZAt(int i) { return momZ == null ? 0f : momZ[i]; }

    /**
     * Sets the momentum of cell {@code i}, promoting this section to {@code FULL} (so that
     * E/mass arrays are materialized alongside the momentum channels).
     *
     * @param i  cell index (0..{@value CELLS}-1)
     * @param px X momentum (kg·m/s)
     * @param py Y momentum (kg·m/s)
     * @param pz Z momentum (kg·m/s)
     */
    public void setMomentum(int i, float px, float py, float pz) {
        promote();
        ensureMomentum();
        momX[i] = px;
        momY[i] = py;
        momZ[i] = pz;
    }

    /**
     * Returns the <em>live</em> momX array (length {@value CELLS}), allocating it (and momY/momZ)
     * if needed. Also promotes E/mass to {@code FULL}.
     */
    public float[] momXArray() {
        promote();
        ensureMomentum();
        return momX;
    }

    /**
     * Returns the <em>live</em> momY array (length {@value CELLS}), allocating it (and momX/momZ)
     * if needed. Also promotes E/mass to {@code FULL}.
     */
    public float[] momYArray() {
        promote();
        ensureMomentum();
        return momY;
    }

    /**
     * Returns the <em>live</em> momZ array (length {@value CELLS}), allocating it (and momX/momY)
     * if needed. Also promotes E/mass to {@code FULL}.
     */
    public float[] momZArray() {
        promote();
        ensureMomentum();
        return momZ;
    }

    // -------------------------------------------------------------------------
    // Dynamic-pressure channel (independent lazy allocation, default 0)
    // -------------------------------------------------------------------------

    /**
     * Allocates the single pressure array (zero-filled by JVM default) if not yet present.
     * Independent of momentum (a section may carry p without momentum, and vice versa).
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
     * E/mass arrays are materialized alongside the pressure channel).
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
     * Also promotes E/mass to {@code FULL}.
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
     * Independent of momentum and pressure (a section may carry swapReady without momentum/p, and vice versa).
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
     * E/mass arrays are materialized alongside the swapReady channel).
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
     * Also promotes E/mass to {@code FULL}.
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
     * cell holds the same enthalpy and mass (and all bookkeeping channels are absent/zero).
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
        float e0 = enthalpy[0];
        float m0 = mass[0];
        for (int i = 1; i < CELLS; i++) {
            if (enthalpy[i] != e0 || mass[i] != m0) {
                return false;
            }
        }
        // Only demote if momentum is absent or all-zero (X, Y and Z) — a nonzero momentum is the
        // velocity-ghost guard: a moving cell must stay FULL so its momentum survives the collapse.
        if (momX != null) {
            for (int i = 0; i < CELLS; i++) {
                if (momX[i] != 0f || momY[i] != 0f || momZ[i] != 0f) {
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
        uniformEnthalpy = e0;
        uniformMass = m0;
        enthalpy = null;
        mass = null;
        momX = null;
        momY = null;
        momZ = null;
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

    /** Whether this section has non-default (non-null) momentum arrays (the snapshot's presence gate). */
    public boolean hasMomentum() {
        return momX != null;
    }

    /** Whether this section has a non-default (non-null) pressure array (the snapshot's presence gate). */
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
     * layer on first call. Also promotes E/mass to {@code FULL} so all three per-cell layers stay
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

    // -------------------------------------------------------------------------
    // Value equality
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} iff this section and {@code o} have the same enthalpy
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
            if (Float.compare(this.enthalpyAt(i), o.enthalpyAt(i)) != 0) {
                return false;
            }
            if (Float.compare(this.massAt(i), o.massAt(i)) != 0) {
                return false;
            }
        }
        return true;
    }
}
