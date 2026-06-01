package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.Material;
import java.util.List;

/** Builds one full-height engine column (CHUNK_N cells, idx = x + 16*y + 6144*z) from the 24 vanilla
 *  sections of a chunk column. matIx comes from live blocks (provided by the SectionSource); empty cells
 *  are ambient-air; a fluid cell whose stored mass is <= 0 (freshly placed/streamed) is seeded once to its
 *  material defaultMass. This is the ONLY legitimate seed in the pipeline.
 *
 *  <p>SIGNATURE GATE (mass-fabrication fix, 2026-06-01): the stored-mass-{@code <=0} test alone cannot
 *  tell a GENUINE new placement (a fluid label that is new this cycle, never simulated) apart from an
 *  ENGINE-DRAINED cell (the engine moved the water out, leaving a still-fluid-labelled cell at 0 kg).
 *  Re-seeding the latter fabricates 1000 kg/cycle. So the seed also requires the cell's current fluid
 *  label to DIFFER from the prior cycle's recorded engine-output species ({@code priorSpecies}, a
 *  section-local LUT index; 0/void = never simulated/unknown). A drained-but-still-water cell has
 *  {@code priorSpecies == current water == matIx} ⇒ it is NOT re-seeded (mass conserved). A genuinely
 *  new placement has {@code priorSpecies == void != water} ⇒ it IS seeded once.</p> */
public final class ColumnAssembler {
    public static final int MIN_SECTION_Y = -4;
    public static final int MAX_SECTION_Y = 19; // inclusive -> 24 sections -> 384 cells
    private static final int SEC = 4096;

    /** Per-section cell view: matIx from live blocks, mass/T from SectionStore (or ambient), and
     *  priorSpecies — the previous cycle's recorded engine-OUTPUT species per cell as a LUT index
     *  (section-local order; 0/void means never-simulated/unknown). The seed gate uses it to seed
     *  ONLY a fluid label that is new relative to priorSpecies. */
    public record SectionCells(char[] matIx, float[] mass, float[] temperature, char[] priorSpecies) {
        /** Back-compat / never-simulated convenience: no prior signature (all-void → every fresh
         *  fluid cell is treated as a genuine new placement, the pre-gate behaviour). */
        public SectionCells(char[] matIx, float[] mass, float[] temperature) {
            this(matIx, mass, temperature, new char[matIx.length]);
        }
    }

    @FunctionalInterface
    public interface SectionSource {
        SectionCells read(int cx, int cz, int sectionY);
    }

    public static ColumnTask assemble(int cx, int cz, List<Material> lut, SectionSource src) {
        int N = RegionMarshaller.CHUNK_N;
        char[] matIx = new char[N];
        float[] mass = new float[N];
        float[] temp = new float[N];
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
                        char mat = cells.matIx()[si];
                        float storedMass = cells.mass()[si];
                        char prior = cells.priorSpecies()[si];
                        Material m = lut.get(mat);
                        float seeded;
                        // Seed a fresh fluid cell ONLY when its label is NEW relative to last cycle's
                        // engine-output species. A genuine placement: prior (void/other) != mat ⇒ seed.
                        // An engine-drained-but-still-fluid cell: prior == mat ⇒ keep 0, no fabrication.
                        if (m.fluid() && storedMass <= 0f && prior != mat) {
                            seeded = m.defaultMass();              // fresh-fluid seed (once)
                        } else {
                            seeded = storedMass;
                        }
                        matIx[ci] = mat;
                        mass[ci] = seeded;
                        temp[ci] = cells.temperature()[si];
                    }
                }
            }
        }
        return new ColumnTask(cx, cz, matIx, mass, temp);
    }

    private ColumnAssembler() {}
}
