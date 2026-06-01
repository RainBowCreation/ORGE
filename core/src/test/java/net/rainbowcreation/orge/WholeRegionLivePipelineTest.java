package net.rainbowcreation.orge;

import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.ColumnAssembler;
import net.rainbowcreation.orge.scheduler.StepValidator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Multi-column acceptance oracle — drives the REAL whole-region pipeline over TWO columns
 * {@code (0,0)} and {@code (1,0)} through a single {@link NativeEngine#stepWorld} call so the
 * engine flows water ACROSS the X seam between them.
 *
 * <p>The engine's tested cross-X/Z-seam mechanism (see {@code ORGE-ENGINE/sim_engine.hpp} the
 * {@code wetPair} same-species seam-leveling block, and {@code tests/world_step_test.cpp} case (b),
 * which seeds a same-species sliver on the far side) is SAME-SPECIES LEVELING: two same-species
 * fluid cells of different mass straddling the seam level toward each other. Cross-seam
 * wetting-into-air is a BANKED engine feature, NOT present in this {@code .so}. So the scenario
 * places a high water cell at the {@code +X} edge of col {@code (0,0)} and a low water cell at the
 * {@code -X} edge of col {@code (1,0)}; the engine levels water from {@code (0,0)} across the seam
 * into {@code (1,0)}.</p>
 *
 * <p>Every cycle asserts: (a) total water across both columns {@code == 1000} (±1e-2); (c) total
 * air mass across both columns is conserved (magnitude-relative tolerance, since the air total is
 * ~2.4e5 kg over ~4.7e5 float32 cells and the §11 leak it guards against is kg-scale per cell).
 * After settling: (b) more water ended up in col {@code (1,0)} than it started with — it crossed
 * the X seam. The per-cycle {@link StepValidator.SpeciesMassLedger} gate enforces the engine's own
 * {@code ε·N} conservation over BOTH columns. Engine output is persisted VERBATIM per column (no
 * reseed, trust the engine).</p>
 */
class WholeRegionLivePipelineTest {

    private static final char VOID = 0, WATER = 1, AIR = 2, STONE = 3;
    private static final List<Material> LUT =
            List.of(TestMaterials.voidMat(), TestMaterials.water(), TestMaterials.air(), TestMaterials.stone());

    private static final int SEC = 4096;
    private static final float AMBIENT_T = 300f;
    private static final float AIR_MASS = 1.2f;

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    /** A 24-section canonical store for one column, air-filled, with verbatim engine write-back. */
    static final class FakeColumn {
        final int cx, cz;
        final char[][] mat = new char[24][SEC];
        final float[][] mass = new float[24][SEC];
        final float[][] temp = new float[24][SEC];

        FakeColumn(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
            for (int s = 0; s < 24; s++) {
                Arrays.fill(mat[s], AIR);
                Arrays.fill(mass[s], AIR_MASS);
                Arrays.fill(temp[s], AMBIENT_T);
            }
        }

        private static int sIdx(int sectionY) { return sectionY + 4; }
        private static int sLocal(int x, int sy, int z) { return x + 16 * sy + 256 * z; }

        void set(int x, int yWorld, int z, char m, float kg, float t) {
            int sectionY = Math.floorDiv(yWorld, 16);
            int sy = Math.floorMod(yWorld, 16);
            mat[sIdx(sectionY)][sLocal(x, sy, z)] = m;
            mass[sIdx(sectionY)][sLocal(x, sy, z)] = kg;
            temp[sIdx(sectionY)][sLocal(x, sy, z)] = t;
        }

        ColumnAssembler.SectionSource source() {
            return (qcx, qcz, sectionY) -> new ColumnAssembler.SectionCells(
                    mat[sIdx(sectionY)].clone(), mass[sIdx(sectionY)].clone(), temp[sIdx(sectionY)].clone());
        }

        void persist(ColumnResult r) {
            for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
                int s = sIdx(sectionY);
                for (int z = 0; z < 16; z++)
                    for (int sy = 0; sy < 16; sy++) {
                        int engineY = sectionY * 16 + sy + 64;
                        for (int x = 0; x < 16; x++) {
                            int ci = x + 16 * engineY + 6144 * z;
                            int si = sLocal(x, sy, z);
                            mat[s][si] = r.matIx()[ci];
                            mass[s][si] = r.mass()[ci];
                            temp[s][si] = r.temperature()[ci];
                        }
                    }
            }
        }

        double speciesMass(char species) {
            double total = 0;
            for (int s = 0; s < 24; s++)
                for (int i = 0; i < SEC; i++)
                    if (mat[s][i] == species) total += mass[s][i];
            return total;
        }

        void floor() {
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    set(x, 0, z, STONE, 2000f, AMBIENT_T);
        }
    }

    @Test
    void waterCrossesXSeamAndConserves() {
        NativeEngine e = engineOrSkip();
        FakeColumn col0 = new FakeColumn(0, 0);
        FakeColumn col1 = new FakeColumn(1, 0);
        col0.floor();
        col1.floor();
        // High water cell at the +X edge of col0 (x=15) and a low water cell at the -X edge of col1 (x=0),
        // both resting on the floor at the SAME y. Both supported (won't drain to 0 -> no reseed). The
        // engine's same-species seam-leveling moves water col0 -> col1 across the X seam. Total = 1000 kg.
        final float col1Start = 100f;
        col0.set(15, 1, 8, WATER, 900f, 290f);
        col1.set(0, 1, 8, WATER, col1Start, 290f);

        double airBefore = col0.speciesMass(AIR) + col1.speciesMass(AIR);
        // Air total is ~2.4e5 kg across ~4.7e5 air cells; a float32 round-trip through the engine each
        // cycle introduces representational noise of ~|air|·1e-7. The conservation GOAL is "no air created
        // or destroyed" — the §11 leak it guards against is +1.2 kg/cell (thousands of kg over cycles),
        // orders of magnitude above this. So the air assertion uses a magnitude-relative tolerance (still
        // far tighter than any real leak); the per-cycle ledger gate enforces the engine's own ε·N.
        double airTol = Math.max(1e-2, airBefore * 1e-6);

        for (int cycle = 0; cycle < 30; cycle++) {
            ColumnTask t0 = ColumnAssembler.assemble(col0.cx, col0.cz, LUT, col0.source());
            ColumnTask t1 = ColumnAssembler.assemble(col1.cx, col1.cz, LUT, col1.source());

            // BOTH columns in ONE call so the engine can flow across the X seam.
            List<ColumnResult> res = e.stepWorld(List.of(t0, t1), LUT, 0.25, OrgeEngine.PASS_ADVECTION);
            ColumnResult r0 = res.get(0), r1 = res.get(1);

            // region-wide per-species conservation gate over BOTH columns in one ledger.
            StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
            ledger.add(r0.mass(), t0.mass(), t0.matIx(), r0.matIx(), LUT);
            ledger.add(r1.mass(), t1.mass(), t1.matIx(), r1.matIx(), LUT);
            assertTrue(ledger.conserved(), "region step must conserve every tracked species (cycle " + cycle + ")");

            col0.persist(r0);
            col1.persist(r1);

            double water = col0.speciesMass(WATER) + col1.speciesMass(WATER);
            assertEquals(1000f, water, 1e-2, "total water conserves to 1000 every cycle (cycle " + cycle + ")");

            double air = col0.speciesMass(AIR) + col1.speciesMass(AIR);
            assertEquals(airBefore, air, airTol, "total air conserved every cycle (cycle " + cycle + ")");
        }

        assertTrue(col1.speciesMass(WATER) > col1Start + 1f,
                "water crossed the X seam into column (1,0): started " + col1Start
                        + " kg, ended " + col1.speciesMass(WATER) + " kg");
    }
}
