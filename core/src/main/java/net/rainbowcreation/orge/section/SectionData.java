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
 * <p>In memory the per-cell channels are parallel {@code float[4096]} arrays, except enthalpy, which is
 * {@code double[4096]}: it is carried at double through the engine seam every tick (issue #44 — a float32
 * runtime store rounded every cell's E once per tick, so a small heat inflow into a massive cell, below
 * half a float ULP of its E, was destroyed). On disk E is persisted at float32 — a ONE-OFF rounding per
 * save→load (≤ ½ ULP(E) per cell, relative 2⁻²⁴), never per tick. On disk a section
 * serializes as {@code UNIFORM} (one E + one mass — the common case far from any heat source) or
 * {@code FULL} (compressed 4096-arrays once a gradient forms). See {@link RegionStore}. The derive
 * to {@code T}/{@code v} (which needs the Material LUT, not held here) lives at every caller
 * boundary, not in this class.</p>
 *
 * <p><b>Composition (DESIGN-LAW v5 #7).</b> A cell is a fixed composition of {@value #SLOTS} unordered
 * {@code (material, mass)} slots sharing the cell's one {@code E}, momentum and {@code P}. Slot 0 is the
 * historical {@link #materialAt}/{@link #massAt} pair (storage only — no slot is special to the
 * physics); slots {@code 1..SLOTS−1} live in a lazily-allocated layer ({@link #slotMaterialAt},
 * {@link #slotMassAt}) whose ids intern into the same per-section palette. An absent (or all-empty)
 * layer means every cell is a one-component cell — exactly what an old one-material world loads as.</p>
 */
public final class SectionData {

    /** 16 × 16 × 16. */
    public static final int CELLS = 4096;

    /** Fallback ambient temperature when a biome value is unavailable (DESIGN.md §5). */
    public static final float DEFAULT_AMBIENT_K = 285.0f;

    /** DESIGN-LAW v5 #7: composition slots per cell (= the engine's {@code MMC_K}); slot 0 is
     *  {@link #materialAt}/{@link #massAt}. */
    public static final int SLOTS = 4;

    /** Length of the slot-1..{@value #SLOTS}−1 layer: index {@code (k−1)·CELLS + cell}. */
    public static final int SLOT_CELLS = (SLOTS - 1) * CELLS;

    public enum Form {
        /** One enthalpy + one mass for the whole section. */
        UNIFORM,
        /** Full per-cell arrays. */
        FULL
    }

    private Form form;
    private double uniformEnthalpy;
    private float uniformMass;
    private double[] enthalpy; // E [J], double (issue #44); null while UNIFORM
    private float[] mass;     // null while UNIFORM
    private MaterialPalette materials; // null until the first per-cell material write
    private float[] momX; // momentum px [kg·m/s]; null until first momentum write or array request
    private float[] momY; // momentum py
    private float[] momZ; // momentum pz
    private float[] p;    // dynamic pressure (Pa-ish gauge, >=0); null until first pressure write/array request
    private char[] slotIx;     // v5 #7 slots 1..SLOTS-1: palette indices, (k-1)*CELLS + i; null until first slot write
    private float[] slotMass;  // v5 #7 slots 1..SLOTS-1: mass [kg], same layout; null together with slotIx
    private float[] swapReady; // §5.3 swap-cadence accumulator (law #7 bookkeeping, dimensionless >=0); null until first write/array request; IN-MEMORY ONLY, NOT serialized

    // [edit-epoch guard] IN-MEMORY ONLY (not serialized, not in equals). The async scheduler reads a
    // section in the snapshot and writes the engine result back a cycle later. An EXTERNAL edit
    // (/orge set, etc.) made into that window would be clobbered by the stale write-back. So an
    // external edit bumps {@code editEpoch}; the snapshot stamps {@code snapshotEpoch} via
    // {@link #markSnapshot()}; the write-back skips a section where {@link #editedSinceSnapshot()} —
    // the edit survives and is re-snapshotted (and simulated) next cycle. Plain longs, server-thread.
    private long editEpoch = 0L;
    private long snapshotEpoch = 0L;

    private SectionData(Form form, double uniformEnthalpy, float uniformMass,
                        double[] enthalpy, float[] mass) {
        this.form = form;
        this.uniformEnthalpy = uniformEnthalpy;
        this.uniformMass = uniformMass;
        this.enthalpy = enthalpy;
        this.mass = mass;
    }

    /**
     * Stored E of a cell whose enthalpy has never been set (issue #54, law §7): the explicit "unset"
     * sentinel {@link net.rainbowcreation.orge.scheduler.ColumnAssembler} honours by encoding
     * {@code E = m·h(T_seed)} once from the seeded mass. Never a temperature.
     */
    public static final double UNSET_ENTHALPY_J = 0.0;

    /**
     * An ambient section materialized for a never-simulated key: every cell at the unset-E sentinel and
     * {@code massKg}. The ambient temperature is NOT stored — the snapshot seeds it (law §7).
     */
    public static SectionData ambient(float massKg) {
        return uniform(UNSET_ENTHALPY_J, massKg);
    }

    /** A never-simulated section: implicitly uniform enthalpy and material default mass. */
    public static SectionData uniform(double enthalpyJ, float massKg) {
        return new SectionData(Form.UNIFORM, enthalpyJ, massKg, null, null);
    }

    public Form form() {
        return form;
    }

    /** Enthalpy E [J] of cell {@code i} (0..4095), expanding from UNIFORM transparently. */
    public double enthalpyAt(int i) {
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
    public static SectionData full(double[] enthalpy, float[] mass) {
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
     * in-memory-only {@code swapReady} channel is never included (law §7). E is narrowed to its float32
     * persisted image (a copy — the one-off save rounding, issue #44); the other arrays are aliased, not
     * copied — the codec only reads them.</p>
     */
    SectionSnapshot snapshot() {
        boolean full = (form == Form.FULL);
        List<Identifier> palette = (materials == null) ? null : materials.palette();
        char[] indices = (materials == null) ? null : materials.indices();
        return new SectionSnapshot(
                form,
                (float) uniformEnthalpy,
                uniformMass,
                full ? persistedEnthalpy(enthalpy) : null,
                full ? mass : null,
                momX, momY, momZ,
                p,
                palette, indices,
                slotIx, slotMass);
    }

    /** The persisted (float32) image of the runtime double E — the one-off save rounding (issue #44). */
    private static float[] persistedEnthalpy(double[] e) {
        float[] out = new float[e.length];
        for (int i = 0; i < e.length; i++) out[i] = (float) e[i];
        return out;
    }

    /** Widen a persisted float32 E layer into the runtime double store (exact). */
    private static double[] widen(float[] e) {
        double[] out = new double[e.length];
        for (int i = 0; i < e.length; i++) out[i] = e[i];
        return out;
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
                ? full(widen(snap.enthalpy()), snap.mass())
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
        if (snap.hasSlots()) {
            s.promote();
            s.slotIx = snap.slotIndices();
            s.slotMass = snap.slotMass();
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
        thaw();
        if (form == Form.FULL) {
            return;
        }
        enthalpy = new double[CELLS];
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
    public void setEnthalpy(int i, double v) {
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

    // [legacy waterlogged seed] IN-MEMORY ONLY (not serialized, not in equals). A section decoded from the
    // pre-composition format (v5) carried no water for its waterlogged blocks; every snapshot that reads it
    // seeds that water (proposal §12 Q4 (B)) until a write-back persists the seeded water and clears the
    // mark (issue #60: a held or failed step lands nothing, so the next snapshot seeds again — once in all).
    private boolean legacyOneComponent;

    /** Mark this section as decoded from the pre-composition (v5) format. */
    void markLegacyOneComponent() {
        legacyOneComponent = true;
    }

    /** Whether this section came from the pre-composition format and no write-back has persisted it since. */
    public boolean isLegacyOneComponent() {
        return legacyOneComponent;
    }

    /** {@link #isLegacyOneComponent()}, clearing the mark (the legacy waterlogged seed happens once). */
    public boolean consumeLegacyOneComponent() {
        boolean was = legacyOneComponent;
        legacyOneComponent = false;
        return was;
    }

    // -------------------------------------------------------------------------
    // Off-thread hand-over (issue #51): a copy-on-write view the scheduler's step thread reads, and
    // adoption of the section that thread built back into the live store object
    // -------------------------------------------------------------------------

    /** Set by {@link #share()}: this section's arrays are also held by a view another thread reads, so the
     *  first mutation copies them first ({@link #thaw()}). Server-thread, like every mutation. */
    private boolean shared;

    /**
     * A view of this section's current state for the scheduler's step thread (issue #51): it holds this
     * section's arrays, palette and composition layer by reference — allocating nothing but the view — and
     * both sides copy them before their next mutation (copy-on-write), so the view keeps the state it was
     * taken at however the live section changes afterwards, and a mutation of the view never reaches the
     * live section. Edit epochs and the legacy mark are copied.
     */
    public SectionData share() {
        shared = true;
        SectionData v = new SectionData(form, uniformEnthalpy, uniformMass, enthalpy, mass);
        v.shared = true;
        v.materials = materials;
        v.momX = momX;
        v.momY = momY;
        v.momZ = momZ;
        v.p = p;
        v.slotIx = slotIx;
        v.slotMass = slotMass;
        v.swapReady = swapReady;
        v.editEpoch = editEpoch;
        v.snapshotEpoch = snapshotEpoch;
        v.legacyOneComponent = legacyOneComponent;
        return v;
    }

    /** Before a mutation of a {@link #share() shared} section: take private copies of everything a view holds. */
    private void thaw() {
        if (!shared) {
            return;
        }
        shared = false;
        enthalpy = enthalpy == null ? null : enthalpy.clone();
        mass = mass == null ? null : mass.clone();
        materials = materials == null ? null : materials.copy();
        momX = momX == null ? null : momX.clone();
        momY = momY == null ? null : momY.clone();
        momZ = momZ == null ? null : momZ.clone();
        p = p == null ? null : p.clone();
        slotIx = slotIx == null ? null : slotIx.clone();
        slotMass = slotMass == null ? null : slotMass.clone();
        swapReady = swapReady == null ? null : swapReady.clone();
    }

    /** Start this (fresh) section's material layer as a copy of {@code from}'s, palette order included. */
    public void inheritPalette(SectionData from) {
        thaw();
        materials = from.materials == null ? null : from.materials.copy();
    }

    /**
     * Take every channel, the palette, the composition layer and the legacy mark of {@code next} by
     * reference — the write-back's O(1) install of a section its worker built off-thread (issue #51).
     * This object's edit epochs are kept: they guard edits made to THIS live section. {@code next} must
     * not be used afterwards (its arrays now belong to this section), nor be a {@link #share() view}.
     */
    public void adopt(SectionData next) {
        shared = false; // every array is replaced: a view of the old ones keeps them to itself
        form = next.form;
        uniformEnthalpy = next.uniformEnthalpy;
        uniformMass = next.uniformMass;
        enthalpy = next.enthalpy;
        mass = next.mass;
        materials = next.materials;
        momX = next.momX;
        momY = next.momY;
        momZ = next.momZ;
        p = next.p;
        slotIx = next.slotIx;
        slotMass = next.slotMass;
        swapReady = next.swapReady;
        legacyOneComponent = next.legacyOneComponent;
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
    public double[] enthalpyArray() {
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
        releaseEmptySlots();
        if (form == Form.UNIFORM) {
            return true;
        }
        // An occupied composition slot is per-cell state a UNIFORM section cannot carry.
        if (slotIx != null) {
            return false;
        }
        double e0 = enthalpy[0];
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
        Identifier id = durableMaterialAt(i);
        return id == null ? MaterialPalette.VACUUM_ID : id;
    }

    /**
     * The DURABLE material id of cell {@code i}, or {@code null} when the cell has none yet (no layer, or
     * a cell the layer's first writer did not touch — {@link MaterialPalette#UNSET_ID}). A {@code null}
     * cell takes its live block's first-touch species; an explicit {@code orge:vacuum} is a durable empty
     * cell (a broken block). Issue #54: materializing a section must not turn its untouched cells vacuum.
     */
    public Identifier durableMaterialAt(int i) {
        if (materials == null) {
            return null;
        }
        Identifier id = materials.get(i);
        return MaterialPalette.UNSET_ID.equals(id) ? null : id;
    }

    /**
     * Sets the material id of cell {@code i} (0..{@value CELLS}-1), lazily allocating the material
     * layer on first call. Also promotes E/mass to {@code FULL} so all three per-cell layers stay
     * aligned for the codec.
     */
    public void setMaterialAt(int i, Identifier id) {
        thaw();
        if (materials == null) {
            materials = MaterialPalette.allUnset();
        }
        promote();
        materials.set(i, id);
    }

    /**
     * The palette slot of cell {@code i}'s material in {@link #rawPalette()}, or {@code -1} when no material
     * layer exists (every cell reads {@code orge:vacuum}). A slot holding {@link MaterialPalette#UNSET_ID}
     * reads as {@code orge:vacuum} through {@link #materialAt}. For per-cell loops that resolve the palette
     * once instead of an id per cell.
     */
    public int materialSlotAt(int i) {
        return materials == null ? -1 : materials.indices()[i];
    }

    /** The palette slot of composition slot {@code k} of cell {@code i} in {@link #rawPalette()}, or
     *  {@code -1} when no composition layer exists (the slot is empty: {@code orge:vacuum}, 0 kg). */
    public int slotMaterialSlotAt(int k, int i) {
        int q = slotIndex(k, i);
        return slotIx == null ? -1 : slotIx[q];
    }

    /** The palette {@link #materialSlotAt}/{@link #slotMaterialSlotAt} index, unset marker included; read only. */
    public List<Identifier> rawPalette() {
        return materials == null ? List.of(MaterialPalette.VACUUM_ID) : materials.palette();
    }

    /**
     * The material palette (size includes the slot-0 vacuum sentinel; the {@link MaterialPalette#UNSET_ID}
     * marker is not a material and is left out). When no material layer is allocated, returns a singleton
     * {@code [VACUUM_ID]}.
     */
    public List<Identifier> palette() {
        return materials == null ? List.of(MaterialPalette.VACUUM_ID)
                : materials.palette().stream().filter(id -> !MaterialPalette.UNSET_ID.equals(id)).toList();
    }

    // -------------------------------------------------------------------------
    // Composition slots 1..SLOTS-1 (DESIGN-LAW v5 #7; lazy, default empty = one-component cell)
    // -------------------------------------------------------------------------

    private static int slotIndex(int k, int i) {
        if (k < 1 || k >= SLOTS) {
            throw new IllegalArgumentException("slot must be in [1," + SLOTS + "), got " + k);
        }
        return (k - 1) * CELLS + i;
    }

    /** Whether this section carries a composition layer (some cell had a slot 1..{@value #SLOTS}−1
     *  written since the layer was last released). */
    public boolean hasSlots() {
        return slotIx != null;
    }

    /** Material id of slot {@code k} (1..{@value #SLOTS}−1) of cell {@code i}; {@code orge:vacuum}
     *  for an empty slot or when no composition layer exists. */
    public Identifier slotMaterialAt(int k, int i) {
        int q = slotIndex(k, i);
        return slotIx == null ? MaterialPalette.VACUUM_ID : materials.palette().get(slotIx[q]);
    }

    /** Mass [kg] of slot {@code k} (1..{@value #SLOTS}−1) of cell {@code i}; {@code 0} when empty. */
    public float slotMassAt(int k, int i) {
        int q = slotIndex(k, i);
        return slotMass == null ? 0f : slotMass[q];
    }

    /**
     * Sets slot {@code k} (1..{@value #SLOTS}−1) of cell {@code i} to {@code (id, massKg)}, lazily
     * allocating the composition layer (and the material palette its ids intern into). Promotes to
     * {@code FULL}. An empty slot is {@code (orge:vacuum, 0)}.
     */
    public void setSlot(int k, int i, Identifier id, float massKg) {
        int q = slotIndex(k, i);
        thaw();
        if (materials == null) {
            materials = MaterialPalette.allUnset();
        }
        promote();
        if (slotIx == null) {
            slotIx = new char[SLOT_CELLS];
            slotMass = new float[SLOT_CELLS];
        }
        slotIx[q] = (char) materials.indexOf(id);
        slotMass[q] = massKg;
    }

    /**
     * Copies the composition layer into {@code ids}/{@code masses} (each length {@value #SLOT_CELLS},
     * index {@code (k−1)·CELLS + cell}); leaves them untouched when this section has no layer (the
     * caller's zero/null default is the empty slot).
     */
    public void copySlotsInto(Identifier[] ids, float[] masses) {
        if (slotIx == null) {
            return;
        }
        List<Identifier> pal = materials.palette();
        for (int q = 0; q < SLOT_CELLS; q++) {
            ids[q] = pal.get(slotIx[q]);
            masses[q] = slotMass[q];
        }
    }

    /** Total cell mass {@code M = Σ_k m_k} [kg] of cell {@code i} (derived, law v5 #7). */
    public float cellMassAt(int i) {
        float m = massAt(i);
        if (slotMass != null) {
            for (int k = 1; k < SLOTS; k++) {
                m += slotMass[(k - 1) * CELLS + i];
            }
        }
        return m;
    }

    /** Drops a composition layer whose every slot is empty (mass 0): it is the absent layer. */
    private void releaseEmptySlots() {
        if (slotMass == null) {
            return;
        }
        for (float m : slotMass) {
            if (m != 0f) {
                return;
            }
        }
        slotIx = null;
        slotMass = null;
    }

    // -------------------------------------------------------------------------
    // Value equality
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} iff this section and {@code o} have the same enthalpy
     * and mass in every cell (including the UNIFORM-vs-FULL cross-comparison).
     *
     * <p>Uses {@link Double#compare(double, double)} / {@link Float#compare(float, float)} for exact
     * equality (no epsilon).
     * Does <em>not</em> override {@link Object#equals} — identity semantics are
     * preserved; use this named helper when value equality is required.</p>
     *
     * @param o the other section to compare
     */
    public boolean equalsValue(SectionData o) {
        for (int i = 0; i < CELLS; i++) {
            if (Double.compare(this.enthalpyAt(i), o.enthalpyAt(i)) != 0) {
                return false;
            }
            if (Float.compare(this.massAt(i), o.massAt(i)) != 0) {
                return false;
            }
        }
        return true;
    }
}
