package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.MaterialPalette;

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
     *  </ul> */
    public record SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                               Identifier[] storedMaterial,
                               float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                               float[] enthalpy) {
        /** Back-compat: swapReady supplied, zero enthalpy (absolute E [J]). */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial,
                            float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial,
                 momX, momY, momZ, p, swapReady, new float[matIx.length]);
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
        int N = RegionMarshaller.CHUNK_N;
        char[] matIx = new char[N];
        float[] mass = new float[N];
        float[] temp = new float[N];
        float[] momX = new float[N];
        float[] momY = new float[N];
        float[] momZ = new float[N];
        float[] p    = new float[N];
        float[] swapReady = new float[N];
        float[] enthalpy = new float[N];
        // EnthalpyCurve lookup (law §6/§7): returns null for an absent/unresolvable id, matching the
        // contract every derive/encode site already uses (id -> registry.get(id).orElse(null)).
        Function<Identifier, Material> lookup = id -> registry.get(id).orElse(null);
        for (int sectionY = MIN_SECTION_Y; sectionY <= MAX_SECTION_Y; sectionY++) {
            SectionCells cells = src.read(cx, cz, sectionY);
            for (int z = 0; z < 16; z++) {
                for (int sy = 0; sy < 16; sy++) {
                    int engineY = sectionY * 16 + sy + 64;
                    int rowBase = 16 * engineY + 6144 * z;        // + x below
                    int secRow = 16 * sy + 256 * z;               // + x below
                    for (int x = 0; x < 16; x++) {
                        int ci = rowBase + x;
                        int si = secRow + x;
                        // Identity: durable STORED material is authoritative when present; else first-touch.
                        Identifier sid = cells.storedMaterial()[si];
                        char mat;
                        if (sid != null) {
                            Material sm = sid.equals(MaterialPalette.VACUUM_ID)
                                    ? MaterialLut.VACUUM            // hardcoded sentinel, not in JSON registry
                                    : registry.getOrFallback(sid);
                            mat = lut.indexOf(sm);                 // appends if absent — stored is authoritative
                        } else {
                            mat = cells.matIx()[si];               // unstored → block's first-touch index
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
                        if (storedMass <= 0f) {
                            if (prior == 0) {
                                seeded = m.defaultMass();         // fresh generation (ambient air/ocean); vacuum's is 0
                            } else {
                                mat = 0;                          // transformed-to-empty ⇒ orge:vacuum (fillable)
                                m = lut.materials().get(0);
                                seeded = 0f;
                            }
                        } else {
                            seeded = storedMass;
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
                        // Thermal truth is EXTENSIVE E [J] (law §7: store extensive, derive intensive).
                        // An authoritative stored E is passed through UNCHANGED. But a seed/ambient cell
                        // (never simulated, or freshly placed) carries E == 0; left as 0 the engine would
                        // derive T = 0 K. ENCODE E = m·h(T_seed) ONCE here (law §6) from the SEEDED mass +
                        // seed temperature — the correct initial condition, NOT a forbidden re-encode (we
                        // only encode when stored E is unset; a massless/unresolvable cell stays 0).
                        float inE = cells.enthalpy()[si];
                        enthalpy[ci] = (inE != 0f)
                                ? inE
                                : (m == null || seeded <= 0f) ? 0f
                                  : (float) EnthalpyCurve.cellE(seeded, m, lookup, temp[ci]);
                    }
                }
            }
        }
        return new ColumnTask(cx, cz, matIx, mass, temp, momX, momY, momZ, p, swapReady, enthalpy);
    }

    private ColumnAssembler() {}
}
