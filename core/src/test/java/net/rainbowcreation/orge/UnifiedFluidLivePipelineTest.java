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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unified-fluid acceptance oracles driven through the REAL live whole-region COLUMN pipeline against
 * the bundled, rebuilt native {@code liborge.so} (ENGINE {@code a473dc9}). Same harness shape as
 * {@link Section11LivePipelineReproTest} / {@link WholeRegionLivePipelineTest}: a fake 24-section
 * canonical store → {@link ColumnAssembler} → {@link NativeEngine#stepWorld} → region
 * {@link StepValidator.SpeciesMassLedger} gate → VERBATIM persist (no reseed, trust the engine).
 *
 * <p>These four oracles encode the Phase-3.x acceptance behaviour through the live pipeline:</p>
 * <ol>
 *   <li><b>Tube sort across a section boundary</b> — a sealed column of light air with one heavy
 *       water cell near the top; after enough cycles the water reaches the column floor (crossing the
 *       old {@code y%16} lines), air ends above, each species EXACTLY conserved every cycle.</li>
 *   <li><b>Cross-chunk into pure air</b> — water at the {@code +X} edge of col {@code (0,0)} over a
 *       stone floor, col {@code (1,0)} PURE air; water crosses the X seam and levels in; conserved.</li>
 *   <li><b>Frozen shelf not penetrated + stone still conducts</b> — water over a frozen stone shelf
 *       never sinks through the stone (mass above stays above; the stone cell's matIx/mass are
 *       unchanged by advection); a separate conduction pass still drives the stone cell's temperature
 *       toward its neighbours (movability and conductivity are independent).</li>
 *   <li><b>Region ledger HOLD on injected non-conservation</b> — feed the {@link
 *       StepValidator.SpeciesMassLedger} an output that fabricates mass; the ledger HOLDs
 *       ({@code conserved() == false}), proving the backstop the pipeline gates on.</li>
 * </ol>
 *
 * <p>The {@code == 1000.0} (±1e-2) and per-species conservation assertions are the GATE — they encode
 * the GOAL, never the observed value.</p>
 */
class UnifiedFluidLivePipelineTest {

    // LUT slot convention (matches the other live tests): 0 void, 1 water, 2 air, 3 stone.
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
    // FakeColumn: a 24-section (sectionY in [-4,19]) canonical store addressed as one column, air-filled,
    // with verbatim engine write-back. Mirrors the harness in the sibling live-pipeline tests.
    // -------------------------------------------------------------------------------------------------
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

        char matAt(int x, int yWorld, int z) {
            int sectionY = Math.floorDiv(yWorld, 16);
            int sy = Math.floorMod(yWorld, 16);
            return mat[sIdx(sectionY)][sLocal(x, sy, z)];
        }

        float massAt(int x, int yWorld, int z) {
            int sectionY = Math.floorDiv(yWorld, 16);
            int sy = Math.floorMod(yWorld, 16);
            return mass[sIdx(sectionY)][sLocal(x, sy, z)];
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

        /** Total stored mass of {@code species} in world Y in [yLo, yHi]. */
        double speciesMassInBand(char species, int yLo, int yHi) {
            double total = 0;
            for (int y = yLo; y <= yHi; y++)
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++)
                        if (matAt(x, y, z) == species) total += massAt(x, y, z);
            return total;
        }

        void stoneFloorAt(int yWorld) {
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    set(x, yWorld, z, STONE, 2000f, AMBIENT_T);
        }
    }

    /** assemble → stepWorld(advection) → region ledger gate → verbatim persist. Single column. */
    private static void liveAdvectCycle(NativeEngine e, FakeColumn col) {
        ColumnTask task = ColumnAssembler.assemble(col.cx, col.cz, LUT, col.source());
        List<ColumnResult> res = e.stepWorld(List.of(task), LUT, 0.25, OrgeEngine.PASS_ADVECTION);
        ColumnResult r = res.get(0);
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(r.mass(), task.mass(), task.matIx(), r.matIx(), LUT);
        assertTrue(ledger.conserved(), "region step must conserve every tracked species");
        col.persist(r);
    }

    // =================================================================================================
    // Oracle 1: tube sort across a 16-block (section) boundary. A sealed column — stone floor at y=0,
    // stone cap at y=48 — of light air with ONE heavy water cell near the top (y=40, above the y=16 and
    // y=32 section lines). Under the engine's molar-mass sort the heavy water sinks to the floor (water
    // M 0.018 > air M 0.002), crossing the old y%16 boundaries; air ends up above it. Water and air are
    // each EXACTLY conserved every cycle.
    // =================================================================================================
    @Test
    void tubeSortAcrossSectionBoundary() {
        NativeEngine e = engineOrSkip();
        FakeColumn col = new FakeColumn(0, 0);
        col.stoneFloorAt(0);   // floor
        col.stoneFloorAt(48);  // cap — seal the tube so nothing leaves the top
        // one heavy water cell near the top, well above two old section boundaries (y=16, y=32).
        col.set(8, 40, 8, WATER, 1000f, 290f);

        double airBefore = col.speciesMass(AIR);
        double airTol = Math.max(1e-2, airBefore * 1e-6); // float32 round-trip noise on the huge air total

        for (int cycle = 0; cycle < 80; cycle++) {
            ColumnTask task = ColumnAssembler.assemble(col.cx, col.cz, LUT, col.source());
            List<ColumnResult> res = e.stepWorld(List.of(task), LUT, 0.25, OrgeEngine.PASS_ADVECTION);
            ColumnResult r = res.get(0);
            StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
            ledger.add(r.mass(), task.mass(), task.matIx(), r.matIx(), LUT);
            assertTrue(ledger.conserved(), "tube step conserves every species (cycle " + cycle + ")");
            col.persist(r);

            assertEquals(1000.0, col.speciesMass(WATER), 1e-2,
                    "water exactly conserved every cycle (cycle " + cycle + ")");
            assertEquals(airBefore, col.speciesMass(AIR), airTol,
                    "air exactly conserved every cycle (cycle " + cycle + ")");
        }

        // The water reached the column floor (the lowest open layer, y=1, just above the stone floor),
        // crossing the y=16 and y=32 section lines on the way down.
        double waterLow = col.speciesMassInBand(WATER, 1, 1);
        double waterHigh = col.speciesMassInBand(WATER, 2, 47);
        assertTrue(waterLow > 900.0,
                "heavy water sank to the column floor across the section boundaries: " + waterLow
                        + " kg at y=1 (started at y=40)");
        assertTrue(waterHigh < 100.0, "almost no water left aloft: " + waterHigh + " kg above y=1");
    }

    // =================================================================================================
    // Oracle 2: cross-chunk into PURE air. Water at the +X edge of col (0,0) over a stone floor; col
    // (1,0) is PURE finite air. The engine flows water across the X seam into genuine air and levels;
    // totals conserved, no doubling/stall, and a MEANINGFUL amount ends up in col (1,0).
    // =================================================================================================
    @Test
    void crossChunkIntoPureAir() {
        NativeEngine e = engineOrSkip();
        FakeColumn col0 = new FakeColumn(0, 0);
        FakeColumn col1 = new FakeColumn(1, 0);
        col0.stoneFloorAt(0);
        col1.stoneFloorAt(0);
        col0.set(15, 1, 8, WATER, 1000f, 290f); // entire water body at the +X edge of col0

        for (int cycle = 0; cycle < 30; cycle++) {
            ColumnTask t0 = ColumnAssembler.assemble(col0.cx, col0.cz, LUT, col0.source());
            ColumnTask t1 = ColumnAssembler.assemble(col1.cx, col1.cz, LUT, col1.source());
            List<ColumnResult> res = e.stepWorld(List.of(t0, t1), LUT, 0.25, OrgeEngine.PASS_ADVECTION);
            ColumnResult r0 = res.get(0), r1 = res.get(1);

            StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
            ledger.add(r0.mass(), t0.mass(), t0.matIx(), r0.matIx(), LUT);
            ledger.add(r1.mass(), t1.mass(), t1.matIx(), r1.matIx(), LUT);
            assertTrue(ledger.conserved(), "two-column step conserves every species (cycle " + cycle + ")");

            col0.persist(r0);
            col1.persist(r1);

            double water = col0.speciesMass(WATER) + col1.speciesMass(WATER);
            assertEquals(1000.0, water, 1e-2,
                    "total water conserves to 1000 every cycle (cycle " + cycle + ")");
        }

        assertTrue(col1.speciesMass(WATER) > 50.0,
                "water crossed the X seam into PURE-AIR column (1,0): ended "
                        + col1.speciesMass(WATER) + " kg (started 0)");
    }

    // =================================================================================================
    // Oracle 3: a FROZEN stone shelf is not penetrated by water, AND the same stone still conducts heat.
    // Movability (viscosity finite) and conductivity (thermal_conductivity) are independent properties.
    //   (a) ADVECTION: a heavy water body rests on a frozen stone shelf above the column floor. Water
    //       never sinks THROUGH the stone — the mass above the shelf stays above, and the shelf cells'
    //       matIx/mass are unchanged by the advection pass.
    //   (b) CONDUCTION: a fresh column with a single cold stone cell between hot neighbours; one
    //       PASS_CONDUCTION step drives the stone cell's temperature UP toward its neighbours (it
    //       conducts heat normally despite being immovable).
    // =================================================================================================
    @Test
    void frozenShelfNotPenetratedButStoneConducts() {
        NativeEngine e = engineOrSkip();

        // (a) advection: water on a frozen stone shelf does not sink through it.
        FakeColumn col = new FakeColumn(0, 0);
        col.stoneFloorAt(0);            // column floor
        col.stoneFloorAt(10);          // the FROZEN stone shelf
        col.set(8, 11, 8, WATER, 1000f, 290f); // a heavy water cell resting on top of the shelf

        // record the shelf cell's matIx/mass before stepping.
        char shelfMatBefore = col.matAt(8, 10, 8);
        float shelfMassBefore = col.massAt(8, 10, 8);
        assertEquals(STONE, shelfMatBefore, "precondition: the shelf cell is stone");

        for (int cycle = 0; cycle < 40; cycle++) liveAdvectCycle(e, col);

        // Water is conserved and stays ABOVE the shelf — none of it appears below the shelf (y in [1,9]).
        assertEquals(1000.0, col.speciesMass(WATER), 1e-2, "water conserved over the frozen shelf");
        double waterBelowShelf = col.speciesMassInBand(WATER, 1, 9);
        assertEquals(0.0, waterBelowShelf, 1e-2,
                "no water penetrated the frozen stone shelf into y in [1,9]: " + waterBelowShelf + " kg");
        double waterAboveShelf = col.speciesMassInBand(WATER, 11, 47);
        assertTrue(waterAboveShelf > 999.0, "water stayed above the shelf: " + waterAboveShelf + " kg");
        // The shelf cell itself is untouched by advection.
        assertEquals(STONE, col.matAt(8, 10, 8), "shelf cell stays stone (immovable, not displaced)");
        assertEquals(shelfMassBefore, col.massAt(8, 10, 8), 1e-2,
                "frozen shelf cell mass unchanged by advection");

        // (b) conduction: the SAME frozen stone still conducts heat toward its neighbours. A single
        // cold (300 K) stone cell at (8,21,8) is surrounded on all SIX faces by hot (1000 K) air. The
        // stone is immovable (frozen) but has a real thermal_conductivity, so a conduction pass warms
        // it. The per-step rise is small (its thermal mass = mass·heatCapacity is large), so we re-PIN
        // the six hot neighbours to 1000 K each cycle (a Dirichlet boundary — they would otherwise cool
        // toward the stone and flatten the gradient) and run many cycles; the cumulative warming is
        // unambiguous. We give the stone a modest stored mass so its thermal capacity is realistic.
        FakeColumn cond = new FakeColumn(0, 0);
        final int sx = 8, sy = 24, sz = 8;          // away from any floor; well inside one section
        final float stoneMass = 100f;               // realistic-but-light thermal mass for the cell
        cond.set(sx, sy, sz, STONE, stoneMass, 300f); // cold stone
        java.util.function.Consumer<FakeColumn> pinHotShell = c -> {
            c.set(sx + 1, sy, sz, AIR, AIR_MASS, 1000f);
            c.set(sx - 1, sy, sz, AIR, AIR_MASS, 1000f);
            c.set(sx, sy + 1, sz, AIR, AIR_MASS, 1000f);
            c.set(sx, sy - 1, sz, AIR, AIR_MASS, 1000f);
            c.set(sx, sy, sz + 1, AIR, AIR_MASS, 1000f);
            c.set(sx, sy, sz - 1, AIR, AIR_MASS, 1000f);
        };
        pinHotShell.accept(cond);
        float stoneTempBefore = tempAt(cond, sx, sy, sz); // baseline = 300 K (the cold stone cell)
        assertEquals(300f, stoneTempBefore, 1e-3, "precondition: stone starts cold at 300 K");

        for (int cycle = 0; cycle < 200; cycle++) {
            pinHotShell.accept(cond); // re-pin the hot Dirichlet shell each cycle
            ColumnTask ct = ColumnAssembler.assemble(cond.cx, cond.cz, LUT, cond.source());
            List<ColumnResult> cres = e.stepWorld(List.of(ct), LUT, 0.25, OrgeEngine.PASS_CONDUCTION);
            cond.persist(cres.get(0));
        }

        float stoneTempAfter = tempAt(cond, sx, sy, sz);
        // The stone's thermal mass (mass·heatCapacity) is large, so the per-step rise is small; over 200
        // cycles it warms by ~0.1 K — small in magnitude but unambiguous (orders of magnitude above
        // float32 noise at 300 K), strictly UP, and still well short of the 1000 K neighbours (it moves
        // TOWARD them, never past). That the temperature changes at all proves the immovable stone still
        // conducts — conductivity and movability are independent.
        assertTrue(stoneTempAfter > stoneTempBefore + 0.05f,
                "frozen stone still conducts: cold stone cell warmed toward its hot neighbours ("
                        + stoneTempBefore + " K -> " + stoneTempAfter + " K)");
        assertTrue(stoneTempAfter < 1000f,
                "stone warmed TOWARD the 1000 K neighbours, not past them: " + stoneTempAfter + " K");
    }

    private static float tempAt(FakeColumn col, int x, int yWorld, int z) {
        int sectionY = Math.floorDiv(yWorld, 16);
        int sy = Math.floorMod(yWorld, 16);
        return col.temp[sectionY + 4][x + 16 * sy + 256 * z];
    }

    // =================================================================================================
    // Oracle 4: the region SpeciesMassLedger HOLDs on an injected non-conservation. Take a real engine
    // step (which conserves), then FABRICATE mass in the output before adding it to a fresh ledger: the
    // ledger must report NOT conserved. This is the exact backstop liveAdvectCycle gates the write-back
    // on, so a non-balancing engine result is rejected (nothing persisted).
    // =================================================================================================
    @Test
    void regionLedgerHoldsOnInjectedNonConservation() {
        NativeEngine e = engineOrSkip();
        FakeColumn col = new FakeColumn(0, 0);
        col.stoneFloorAt(0);
        col.set(8, 1, 8, WATER, 1000f, 290f);

        ColumnTask task = ColumnAssembler.assemble(col.cx, col.cz, LUT, col.source());
        List<ColumnResult> res = e.stepWorld(List.of(task), LUT, 0.25, OrgeEngine.PASS_ADVECTION);
        ColumnResult r = res.get(0);

        // Sanity: the honest engine output DOES conserve.
        StepValidator.SpeciesMassLedger honest = new StepValidator.SpeciesMassLedger();
        honest.add(r.mass(), task.mass(), task.matIx(), r.matIx(), LUT);
        assertTrue(honest.conserved(), "precondition: the honest engine step conserves");

        // Fabricate water from nothing in the output mass array, on a water-labelled cell. The ledger
        // tolerance is ε·totalCells (ε = 1e-2 kg/cell over CHUNK_N = 98304 cells ≈ 983 kg), so the
        // injection must exceed that to prove the HOLD — we add 5000 kg (well past tolerance), a
        // gross, unambiguous non-conservation that the backstop MUST reject.
        float[] tampered = r.mass().clone();
        char[] tamperedMat = r.matIx().clone();
        int waterCell = -1;
        for (int i = 0; i < tamperedMat.length; i++) {
            if (tamperedMat[i] == WATER) { waterCell = i; break; }
        }
        assertTrue(waterCell >= 0, "found a water output cell to tamper with");
        tampered[waterCell] += 5000f; // +5000 kg water from nothing — a non-conservation injection

        StepValidator.SpeciesMassLedger tampedLedger = new StepValidator.SpeciesMassLedger();
        tampedLedger.add(tampered, task.mass(), task.matIx(), tamperedMat, LUT);
        assertFalse(tampedLedger.conserved(),
                "ledger HOLDs: a fabricated +5000 kg water output is rejected (not conserved)");
    }
}
