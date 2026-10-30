package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Builds one full-height engine column (CHUNK_N cells, idx = x + 16*y + 6144*z) from the 24 vanilla
 *  sections of a chunk column.
 *
 *  <p>IDENTITY (durable-material §): a cell's material is the DURABLE STORED material when present
 *  (see {@link SectionCells#storedMaterial}, supplied per-cell by the {@link SectionSource} from the
 *  {@code SectionStore}'s material layer) — the stored id is AUTHORITATIVE and is resolved into the
 *  batch LUT by appending (so a stored species not yet pulled in by a live block still gets a slot).
 *  For cells whose section has NO stored material layer the stored id is {@code null} and identity
 *  falls back to the block's FIRST-TOUCH material (the {@code matIx} the source precomputed). The
 *  stored {@code orge:vacuum} sentinel resolves to LUT index 0 ({@link MaterialLut#VACUUM}), NOT the
 *  registry fallback.</p>
 *
 *  <p>SEED: a cell whose stored mass is {@code <= 0} (freshly placed/streamed) is seeded once to its
 *  material's {@code defaultMass}. This is the ONLY legitimate seed in the pipeline and is no longer
 *  fluid-only — a fresh SOLID cell now seeds its {@code defaultMass} too (durable-material bug-3 prep).
 *  Vacuum's {@code defaultMass} is 0, so seeding a vacuum cell is harmless.</p>
 *
 *  <p>SIGNATURE GATE (mass-fabrication fix, 2026-06-01): the stored-mass-{@code <=0} test alone cannot
 *  tell a GENUINE new placement (a label that is new this cycle, never simulated) apart from an
 *  ENGINE-DRAINED cell (the engine moved the substance out, leaving a still-labelled cell at 0 kg).
 *  Re-seeding the latter fabricates mass/cycle. So the seed also requires the cell's current
 *  label to DIFFER from the prior cycle's recorded engine-output species ({@code priorSpecies}, a
 *  section-local LUT index; 0/void = never simulated/unknown). A drained-but-same-species cell has
 *  {@code priorSpecies == current == matIx} ⇒ it is NOT re-seeded (mass conserved). A genuinely
 *  new placement has {@code priorSpecies == void != mat} ⇒ it IS seeded once.</p> */
public final class ColumnAssembler {
    public static final int MIN_SECTION_Y = -4;
    public static final int MAX_SECTION_Y = 19; // inclusive -> 24 sections -> 384 cells
    private static final int SEC = 4096;

    /** Per-section cell view.
     *  <ul>
     *    <li>{@code matIx} — the block's FIRST-TOUCH index (precomputed by the source); used as identity
     *        only for cells with no stored material.</li>
     *    <li>{@code mass}/{@code temperature} — from the SectionStore (or ambient).</li>
     *    <li>{@code priorSpecies} — the previous cycle's recorded engine-OUTPUT species per cell as a
     *        LUT index (section-local order; 0/void means never-simulated/unknown). The seed gate uses
     *        it to seed ONLY a label that is new relative to priorSpecies.</li>
     *    <li>{@code storedMaterial} — the DURABLE per-cell stored material id (authoritative), or
     *        {@code null} = the cell's section has no stored layer ⇒ use first-touch {@code matIx}.
     *        Length 4096.</li>
     *    <li>{@code momX}/{@code momY}/{@code momZ} — per-cell EXTENSIVE momentum p [kg·m/s] (law §7;
     *        §1.1 persisted extensive set) sourced RAW from the SectionStore, or all-zero for
     *        never-simulated / back-compat callers. Crosses to the engine with NO conversion (mirror of
     *        the enthalpy channel — never down-converted to velocity here). Length 4096.</li>
     *    <li>{@code p} — per-cell dynamic pressure (Pa-ish gauge, >=0) from the SectionStore, or
     *        all-zero for never-simulated / back-compat callers. Length 4096.</li>
     *    <li>{@code swapReady} — per-cell swap-cadence accumulator (law #7 / §5.3 bookkeeping,
     *        dimensionless >=0); all-zero for never-simulated / back-compat callers. Length 4096.</li>
     *    <li>{@code enthalpy} — per-cell ABSOLUTE E [J] (law §6/§7 stored-extensive thermal truth) read
     *        from the SectionStore; all-zero for never-simulated / back-compat callers. Length 4096.</li>
     *    <li>{@code slotMaterial}/{@code slotMass} — the stored DESIGN-LAW v5 #7 composition slots
     *        1..K−1, index {@code (k−1)·4096 + si} (length {@link SectionData#SLOT_CELLS}); a slot with
     *        mass {@code <= 0} is empty (its id is ignored). All-empty for one-component cells.</li>
     *    <li>{@code waterlogSeed} — per cell, the water [kg] a {@code waterlogged} live block holds,
     *        {@code ρ_water·V_pore} of its host (0 elsewhere). Seeded as a second component (proposal §12
     *        Q4 (B)) when {@code seedWaterlog} — the section was never simulated, or it was loaded from the
     *        pre-composition format, whose waterlogged blocks carried no water (seeded once) — and into a
     *        genuinely new cell (the seed gate below). Every seed is a boundary placement
     *        ({@link WaterlogSeeding}).</li>
     *    <li>{@code restCount} — per-cell rest-freeze counter (consecutive unchanged engine steps, in-memory
     *        only); all-zero for never-simulated / back-compat callers. Length 4096.</li>
     *  </ul> */
    public record SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                               Identifier[] storedMaterial,
                               float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                               double[] enthalpy, Identifier[] slotMaterial, float[] slotMass,
                               float[] waterlogSeed, boolean seedWaterlog, float[] restCount) {
        /** Back-compat: rest counter zero. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial,
                            float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                            double[] enthalpy, Identifier[] slotMaterial, float[] slotMass,
                            float[] waterlogSeed, boolean seedWaterlog) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial, momX, momY, momZ, p, swapReady,
                 enthalpy, slotMaterial, slotMass, waterlogSeed, seedWaterlog, new float[matIx.length]);
        }
        /** Back-compat: no waterlogged block in the section. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial,
                            float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                            double[] enthalpy, Identifier[] slotMaterial, float[] slotMass) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial, momX, momY, momZ, p, swapReady,
                 enthalpy, slotMaterial, slotMass, new float[matIx.length], false);
        }
        /** Back-compat: enthalpy supplied, every cell one-component (composition slots 1..K−1 empty). */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial,
                            float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                            double[] enthalpy) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial, momX, momY, momZ, p, swapReady,
                 enthalpy, new Identifier[SectionData.SLOT_CELLS], new float[SectionData.SLOT_CELLS]);
        }
        /** Back-compat: swapReady supplied, zero enthalpy (absolute E [J]). */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial,
                            float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial,
                 momX, momY, momZ, p, swapReady, new double[matIx.length]);
        }
        /** Back-compat: pressure supplied, zero swap-cadence accumulator, zero enthalpy. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial,
                            float[] momX, float[] momY, float[] momZ, float[] p) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial,
                 momX, momY, momZ, p, new float[matIx.length]);
        }
        /** Back-compat: momentum supplied, zero pressure, zero swap-cadence accumulator. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial,
                            float[] momX, float[] momY, float[] momZ) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial,
                 momX, momY, momZ, new float[matIx.length]);
        }
        /** Convenience: prior signature + stored material layer, zero momentum, zero pressure. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial,
                 new float[matIx.length], new float[matIx.length], new float[matIx.length]);
        }
        /** Back-compat: prior signature, no stored material layer, zero momentum, zero pressure. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies) {
            this(matIx, mass, temperature, priorSpecies, new Identifier[matIx.length]);
        }
        /** Back-compat / never-simulated: no prior signature, no stored layer, zero velocity/pressure. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature) {
            this(matIx, mass, temperature, new char[matIx.length], new Identifier[matIx.length]);
        }
    }

    @FunctionalInterface
    public interface SectionSource {
        SectionCells read(int cx, int cz, int sectionY);
    }

    public static ColumnTask assemble(int cx, int cz, MaterialLut lut, MaterialRegistry registry,
                                      SectionSource src) {
        return assemble(cx, cz, lut, registry, src, new WaterlogSeeding.Seeds());
    }

    /** {@link #assemble(int, int, MaterialLut, MaterialRegistry, SectionSource)}, recording into
     *  {@code waterlog} the water + enthalpy each section's waterlogged blocks were seeded with, for the
     *  write-back to book as a boundary placement (issue #60). */
    public static ColumnTask assemble(int cx, int cz, MaterialLut lut, MaterialRegistry registry,
                                      SectionSource src, WaterlogSeeding.Seeds waterlog) {
        int N = RegionMarshaller.CHUNK_N;
        char[] matIx = new char[N];
        float[] mass = new float[N];
        float[] temp = new float[N];
        float[] momX = new float[N];
        float[] momY = new float[N];
        float[] momZ = new float[N];
        float[] p    = new float[N];
        float[] swapReady = new float[N];
        float[] restCount = new float[N];
        double[] enthalpy = new double[N];
        char[] slotIx = new char[RegionMarshaller.SLOT_N];
        float[] slotMass = new float[RegionMarshaller.SLOT_N];
        // EnthalpyCurve lookup (law §6/§7): returns null for an absent/unresolvable id, matching the
        // contract every derive/encode site already uses (id -> registry.get(id).orElse(null)).
        Function<Identifier, Material> lookup = id -> registry.get(id).orElse(null);
        // Batch-LUT index per stored id, resolved once per column (a registry + LUT lookup per cell otherwise).
        Map<Identifier, Character> storedIx = new HashMap<>();
        // Last stored id -> index: a palette-expanded section repeats the same Identifier instance cell after
        // cell, so the identity check skips the hash lookup.
        Identifier lastSid = null;
        char lastSidIx = 0;
        Material lastEMat = null;
        float lastEMass = Float.NaN, lastET = Float.NaN;
        double lastE = 0.0;
        Material water = registry.get(WATER_ID).orElse(null);
        for (int sectionY = MIN_SECTION_Y; sectionY <= MAX_SECTION_Y; sectionY++) {
            SectionCells cells = src.read(cx, cz, sectionY);
            for (int z = 0; z < 16; z++) {
                for (int sy = 0; sy < 16; sy++) {
                    int engineY = sectionY * 16 + sy + 64;
                    int rowBase = RegionMarshaller.colIdx(0, engineY, z);   // + x below
                    int secRow = 16 * sy + 256 * z;               // + x below
                    for (int x = 0; x < 16; x++) {
                        int ci = rowBase + x;
                        int si = secRow + x;
                        // Identity: durable STORED material is authoritative when present; else first-touch.
                        Identifier sid = cells.storedMaterial()[si];
                        char mat;
                        if (sid != null) {
                            if (sid != lastSid) {
                                lastSidIx = storedIx.computeIfAbsent(sid, id -> storedIndex(id, lut, registry)); // appends if absent — stored is authoritative
                                lastSid = sid;
                            }
                            mat = lastSidIx;
                        } else {
                            mat = cells.matIx()[si];               // unstored → block's first-touch index
                        }
                        // v5 #7 composition slots 1..K−1 cross RAW (stored ids resolved into the batch LUT
                        // exactly like slot 0's stored id); an empty slot crosses as (0, 0).
                        float slotTotal = 0f;
                        for (int k = 1; k < SectionData.SLOTS; k++) {
                            float sm = cells.slotMass()[(k - 1) * SEC + si];
                            if (sm > 0f) {
                                int q = (k - 1) * N + ci;
                                slotIx[q] = storedIx.computeIfAbsent(cells.slotMaterial()[(k - 1) * SEC + si],
                                        id -> storedIndex(id, lut, registry));
                                slotMass[q] = sm;
                                slotTotal += sm;
                            }
                        }
                        float storedMass = cells.mass()[si];
                        char prior = cells.priorSpecies()[si];
                        Material m = lut.materials().get(mat);
                        float seeded;
                        // EMPTY-CELL IDENTITY (user rule 2026-06-19): a cell that TRANSFORMED to empty —
                        // a block broken, or a fluid fully drained out — becomes orge:vacuum (a fillable
                        // empty cell), NEVER a sub-min ghost parcel of its old / first-touch species (an
                        // orge:air cell below its min_mass cohesion floor refuses fluid inflow). Only a
                        // NEVER-SIMULATED cell (prior == the VOID sentinel 0) is genuine fresh terrain and
                        // seeds its rest mass — ambient air, ocean water, etc. prior is last cycle's
                        // engine-output species, so VOID(0) ⇔ never simulated ⇔ generation; any REAL prior
                        // ⇔ it was something and is now empty ⇔ vacuum. Self-healing: emitting matIx 0 makes
                        // the write-back persist orge:vacuum, so next cycle sid==vacuum keeps it vacuum (no
                        // air re-seed churn). Conservation-safe: only fires at storedMass ≤ 0 (≈0 kg).
                        // A cell whose OTHER slots still hold mass is not empty: never seed it (that would
                        // fabricate slot-0 mass, e.g. after a reload empties the prior-species tracker).
                        if (storedMass <= 0f) {
                            if (prior == 0 && slotTotal <= 0f) {
                                seeded = m.defaultMass();         // fresh generation (ambient air/ocean); vacuum's is 0
                            } else {
                                mat = 0;                          // transformed-to-empty ⇒ orge:vacuum (fillable)
                                m = lut.materials().get(0);
                                seeded = 0f;
                            }
                        } else {
                            seeded = storedMass;
                        }
                        // A waterlogged block's water (proposal §12 Q4 (B)): seeded once, as a second
                        // component, into a never-simulated / legacy (pre-composition) section or a new
                        // placement, beside a host that is there and holds no other component. A simulated
                        // cell whose water drained is never re-seeded (no fabrication).
                        float wl = cells.waterlogSeed()[si];
                        boolean seedWater = wl > 0f && water != null && slotTotal <= 0f && mat != 0 && seeded > 0f
                                && (cells.seedWaterlog() || (storedMass <= 0f && prior == 0));
                        if (seedWater) {
                            int q = ci;                                    // slot 1: (1−1)·N + ci
                            slotIx[q] = lut.indexOf(water);
                            slotMass[q] = wl;
                        }
                        matIx[ci] = mat;
                        mass[ci] = seeded;
                        temp[ci] = cells.temperature()[si];
                        // Extensive momentum p [kg·m/s] copied RAW (mirror of the enthalpy channel) —
                        // crosses to the engine with NO velocity conversion (law §7 / §1.1).
                        momX[ci] = cells.momX()[si];
                        momY[ci] = cells.momY()[si];
                        momZ[ci] = cells.momZ()[si];
                        p[ci]    = cells.p()[si];
                        swapReady[ci] = cells.swapReady()[si];
                        restCount[ci] = cells.restCount()[si];
                        // Thermal truth is EXTENSIVE E [J] (law §7: store extensive, derive intensive).
                        // An authoritative stored E is passed through UNCHANGED. But a seed/ambient cell
                        // (never simulated, or freshly placed) carries E == 0; left as 0 the engine would
                        // derive T = 0 K. ENCODE E = m·h(T_seed) ONCE here (law §6) from the SEEDED mass +
                        // seed temperature — the correct initial condition, NOT a forbidden re-encode (we
                        // only encode when stored E is unset; a massless/unresolvable cell stays 0).
                        double inE = cells.enthalpy()[si];
                        if (inE != 0.0) {
                            enthalpy[ci] = inE;
                        } else if (m == null || seeded <= 0f) {
                            enthalpy[ci] = 0.0;
                        } else {
                            // An ambient (uniform) section stores no per-cell E, so every cell re-encodes the
                            // same (mass, material, T): reuse the last encode when the inputs repeat.
                            if (m != lastEMat || seeded != lastEMass || temp[ci] != lastET) {
                                lastE = EnthalpyCurve.cellE(seeded, m, lookup, temp[ci]);
                                lastEMat = m; lastEMass = seeded; lastET = temp[ci];
                            }
                            enthalpy[ci] = lastE;
                        }
                        // The seeded water enters at the cell's temperature (E is extensive: Σ m_k·h_k(T)),
                        // recorded as stored so its boundary ledger closes exactly (issue #60).
                        if (seedWater) {
                            double before = enthalpy[ci];
                            enthalpy[ci] = before + EnthalpyCurve.cellE(wl, water, lookup, temp[ci]);
                            waterlog.add(sectionY, wl, enthalpy[ci] - before);
                        }
                    }
                }
            }
        }
        return new ColumnTask(cx, cz, matIx, mass, temp, momX, momY, momZ, p, swapReady, enthalpy,
                              slotIx, slotMass, restCount);
    }

    /** Water [kg] a waterlogged block of {@code host} holds: {@code ρ_water · V_pore}, with the open volume
     *  {@code V_pore = 1 m³ − defaultMass/ρ_p} of a cell at its rest mass (0 for a host with no open volume,
     *  or when no water material is registered). */
    public static float waterlogSeedKg(Material host, Material water) {
        if (host == null || water == null) {
            return 0f;
        }
        float rhoP = Math.max(host.particleDensity(), host.defaultMass());
        float pore = rhoP > 0f ? 1f - host.defaultMass() / rhoP : 0f;
        return pore > 0f ? Math.max(water.particleDensity(), water.defaultMass()) * pore : 0f;
    }

    /** The material a waterlogged block's water is ({@code orge:water}). */
    public static final Identifier WATER_ID = Identifier.fromNamespaceAndPath("orge", "water");

    /** Batch-LUT index of a STORED material id: the {@code orge:vacuum} sentinel is LUT index 0 (not in
     *  the JSON registry); any other id resolves through the registry to its fixed stable-table slot. */
    private static char storedIndex(Identifier sid, MaterialLut lut, MaterialRegistry registry) {
        Material sm = sid.equals(MaterialPalette.VACUUM_ID)
                ? MaterialLut.VACUUM            // hardcoded sentinel, not in JSON registry
                : registry.getOrFallback(sid);
        return lut.indexOf(sm);
    }

    private ColumnAssembler() {}
}
