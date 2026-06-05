package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.MaterialPalette;

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
     *    <li>{@code velX}/{@code velY}/{@code velZ} — per-cell velocity (m/s) from the SectionStore,
     *        or all-zero for never-simulated / back-compat callers. Length 4096.</li>
     *  </ul> */
    public record SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                               Identifier[] storedMaterial,
                               float[] velX, float[] velY, float[] velZ) {
        /** Convenience: prior signature + stored material layer, zero velocity. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies,
                            Identifier[] storedMaterial) {
            this(matIx, mass, temperature, priorSpecies, storedMaterial,
                 new float[matIx.length], new float[matIx.length], new float[matIx.length]);
        }
        /** Back-compat: prior signature, no stored material layer, zero velocity. */
        public SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies) {
            this(matIx, mass, temperature, priorSpecies, new Identifier[matIx.length]);
        }
        /** Back-compat / never-simulated: no prior signature, no stored layer, zero velocity. */
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
        float[] velX = new float[N];
        float[] velY = new float[N];
        float[] velZ = new float[N];
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
                        // Seed a fresh cell ONLY when its label is NEW relative to last cycle's engine-output
                        // species. Genuine placement: prior (void/other) != mat ⇒ seed defaultMass.
                        // Engine-drained-but-same-species cell: prior == mat ⇒ keep 0, no fabrication.
                        if (storedMass <= 0f && prior != mat) {
                            seeded = m.defaultMass();              // fresh seed (once); vacuum's is 0 (harmless)
                        } else {
                            seeded = storedMass;
                        }
                        matIx[ci] = mat;
                        mass[ci] = seeded;
                        temp[ci] = cells.temperature()[si];
                        velX[ci] = cells.velX()[si];
                        velY[ci] = cells.velY()[si];
                        velZ[ci] = cells.velZ()[si];
                    }
                }
            }
        }
        return new ColumnTask(cx, cz, matIx, mass, temp, velX, velY, velZ);
    }

    private ColumnAssembler() {}
}
