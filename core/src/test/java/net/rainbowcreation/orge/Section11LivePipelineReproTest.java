package net.rainbowcreation.orge;

import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
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
 * §11 acceptance oracle — drives the REAL live whole-region COLUMN pipeline against the bundled
 * native {@code liborge.so} and asserts EXACT mass conservation (== 1000.0 ± 1e-2) for the four
 * symptoms that previously fabricated/lost/doubled mass:
 *
 * <ol>
 *   <li>place one water cell into an air-filled column;</li>
 *   <li>spread 1000 kg water across a 2-wide floor;</li>
 *   <li>drop water across the OLD section boundary ({@code y % 16 == 0}) inside ONE column;</li>
 *   <li>a long horizontal wetting trough.</li>
 * </ol>
 *
 * <p>The discipline (spec §9): assemble a full-height column from a fake 24-section store, take one
 * {@link NativeEngine#stepWorld} step, gate it region-wide with {@link StepValidator.SpeciesMassLedger},
 * and persist the engine output VERBATIM back into the fake store — NO reseed, NO geometry
 * re-derivation. The assertions encode the GOAL (1000.0), never the observed value.</p>
 */
class Section11LivePipelineReproTest {

    // ---- LUT slot convention (matches ColumnAssembler/TestMaterials): 0 void, 1 water, 2 air, 3 stone.
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

    // -------------------------------------------------------------------------------------------------
    // FakeColumn: a 24-section (sectionY in [-4,19]) canonical store addressed as one column. Holds the
    // live block material, stored mass, and temperature per section cell; exposes a ColumnAssembler
    // SectionSource and persists a ColumnResult back by the inverse engine index map.
    // -------------------------------------------------------------------------------------------------
    static final class FakeColumn {
        final int cx, cz;
        // sectionIndex = sectionY + 4, range [0,24); each section is a SEC-long cell array.
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

        /** Place a block (mat + stored mass + T) at world coords. yWorld in [-64, 319]. */
        void set(int x, int yWorld, int z, char m, float kg, float t) {
            int sectionY = Math.floorDiv(yWorld, 16);
            int sy = Math.floorMod(yWorld, 16);
            int s = sIdx(sectionY), i = sLocal(x, sy, z);
            mat[s][i] = m; mass[s][i] = kg; temp[s][i] = t;
        }

        ColumnAssembler.SectionSource source() {
            return (qcx, qcz, sectionY) -> {
                int s = sIdx(sectionY);
                return new ColumnAssembler.SectionCells(
                        mat[s].clone(), mass[s].clone(), temp[s].clone());
            };
        }

        /** Write engine output back VERBATIM via the inverse index map (no reseed, trust engine). */
        void persist(ColumnResult r) {
            for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
                int s = sIdx(sectionY);
                for (int z = 0; z < 16; z++) {
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
        }

        /** Total stored mass of the given species across all sections. */
        double speciesMass(char species) {
            double total = 0;
            for (int s = 0; s < 24; s++)
                for (int i = 0; i < SEC; i++)
                    if (mat[s][i] == species) total += mass[s][i];
            return total;
        }
    }

    // -------------------------------------------------------------------------------------------------
    // liveCycle: the one true pipeline step. assemble -> stepWorld -> region ledger gate -> persist.
    // -------------------------------------------------------------------------------------------------
    private static void liveCycle(NativeEngine e, FakeColumn col) {
        ColumnTask task = ColumnAssembler.assemble(col.cx, col.cz, LUT, col.source());
        List<ColumnResult> res = e.stepWorld(List.of(task), LUT, 0.25, OrgeEngine.PASS_ADVECTION);
        ColumnResult r = res.get(0);
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(r.mass(), task.mass(), task.matIx(), r.matIx(), LUT);
        assertTrue(ledger.conserved(), "region step must conserve every tracked species");
        col.persist(r);
    }

    // engine column index for a world (x, yWorld, z).
    private static int colIdx(int x, int yWorld, int z) { return x + 16 * (yWorld + 64) + 6144 * z; }

    // =================================================================================================
    // Scenario 1: place water into air -> total water == 1000.
    // =================================================================================================
    @Test
    void repro_placeWaterIntoAir() {
        NativeEngine e = engineOrSkip();
        FakeColumn col = new FakeColumn(0, 0);
        // stone floor at yWorld=0 across the whole column so water doesn't fall out the bottom forever.
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                col.set(x, 0, z, STONE, 2000f, AMBIENT_T);
        // one freshly-placed water block resting on the floor (stored mass 0 -> seeded once to 1000).
        col.set(8, 1, 8, WATER, 0f, 290f);

        for (int cycle = 0; cycle < 40; cycle++) liveCycle(e, col);

        double water = col.speciesMass(WATER);
        assertEquals(1000f, water, 1e-2, "water placed into air conserves to 1000");
    }

    // =================================================================================================
    // Scenario 2: 1000 kg water on a 2-wide floor spreads -> total == 1000 (~500 + 500).
    // =================================================================================================
    @Test
    void repro_spreadInto2Cells() {
        NativeEngine e = engineOrSkip();
        FakeColumn col = new FakeColumn(0, 0);
        // stone floor; a 2-cell-wide trough is just open air above the floor at (7..8, y=1).
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                col.set(x, 0, z, STONE, 2000f, AMBIENT_T);
        // a full water column cell at (8,1,8) that should level into its (7,1,8) neighbour.
        col.set(8, 1, 8, WATER, 1000f, 290f);

        for (int cycle = 0; cycle < 40; cycle++) liveCycle(e, col);

        double water = col.speciesMass(WATER);
        assertEquals(1000f, water, 1e-2, "spread across 2 cells conserves to 1000");
    }

    // =================================================================================================
    // Scenario 3: drop water across the OLD section boundary (y%16==0) inside ONE column -> no doubling.
    // =================================================================================================
    @Test
    void repro_crossSeamDrop() {
        NativeEngine e = engineOrSkip();
        FakeColumn col = new FakeColumn(0, 0);
        // stone floor at yWorld=0; water dropped from y=16 (the section 0/1 boundary) must fall to it.
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                col.set(x, 0, z, STONE, 2000f, AMBIENT_T);
        col.set(8, 16, 8, WATER, 1000f, 290f);

        for (int cycle = 0; cycle < 60; cycle++) liveCycle(e, col);

        double water = col.speciesMass(WATER);
        assertEquals(1000f, water, 1e-2,
                "fall across the old section boundary conserves to 1000 (no doubling)");
    }

    // =================================================================================================
    // Scenario 4: a long horizontal wetting trough -> total water == 1000 (no +1.2/cell growth).
    // =================================================================================================
    @Test
    void repro_wetting() {
        NativeEngine e = engineOrSkip();
        FakeColumn col = new FakeColumn(0, 0);
        // stone floor; a long open air run at y=1 (the whole z=8 row); one water plug at one end.
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                col.set(x, 0, z, STONE, 2000f, AMBIENT_T);
        col.set(0, 1, 8, WATER, 1000f, 290f);

        for (int cycle = 0; cycle < 60; cycle++) liveCycle(e, col);

        double water = col.speciesMass(WATER);
        assertEquals(1000f, water, 1e-2, "wetting trough conserves to 1000 (no +1.2/cell)");
    }
}
