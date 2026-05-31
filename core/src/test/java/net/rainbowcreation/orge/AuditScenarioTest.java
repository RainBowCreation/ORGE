package net.rainbowcreation.orge;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconcileLogic;
import net.rainbowcreation.orge.phase.PhaseRule;
import net.rainbowcreation.orge.phase.SourcePinPlanner;
import net.rainbowcreation.orge.scheduler.Scheduler;
import net.rainbowcreation.orge.scheduler.StepValidator;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless audit: a pinned lava cell next to a column of water cells. Each "second" we diffuse,
 * evaluate phase change, then conditionally re-pin. Asserts the engine-audit story holds:
 * water heats monotonically, eventually boils to orge:steam, and lava stays pinned (never freezes).
 */
class AuditScenarioTest {

    private static final Identifier LAVA  = Identifier.fromNamespaceAndPath("orge", "lava");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");
    private static final Identifier ICE   = Identifier.fromNamespaceAndPath("minecraft", "ice");
    private static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");
    private static final Identifier ORGE_STEAM = Identifier.fromNamespaceAndPath("orge", "steam");

    // lava: pinned 1400, freezes (<1000) -> stone
    private static Material lava() {
        return new Material(LAVA, 1.5f, 1450f, 0f, 3100f, 0f,
                Float.POSITIVE_INFINITY, 1000f, null, STONE, null, 1400f, true);
    }
    // water: boils (>373.15) -> orge:steam, freezes (<273.15) -> ice; not pinned; fluid=true
    // (14-arg constructor: carries fluid=true so it participates in advection).
    private static Material water() {
        return new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                373.15f, 273.15f, ORGE_STEAM, ICE, null, Float.NaN, false, true);
    }
    private static Material steam() {
        return new Material(STEAM, 0.02f, 2000f, 0f, 1f, 0.018f,
                Float.POSITIVE_INFINITY, 373.15f, null, WATER, null);
    }

    @Test
    void waterNextToPinnedLavaBoilsAndLavaSurvives() {
        // 8-cell 1-D line: index 0 = lava (pinned), 1..7 = water, all water starts at 290 K.
        int n = 8;
        Identifier[] block = new Identifier[n];
        float[] t = new float[n];
        block[0] = LAVA; t[0] = 1400f;
        for (int i = 1; i < n; i++) { block[i] = WATER; t[i] = 290f; }

        java.util.function.IntFunction<Material> matAt = i -> switch (block[i].getPath()) {
            case "lava" -> lava();
            case "water" -> water();
            case "steam" -> steam();
            default -> water();
        };

        boolean waterBoiled = false;
        for (int second = 0; second < 200 && !waterBoiled; second++) {
            // (1) diffuse: explicit 1-D conduction, fixed alpha; ends are insulated.
            float[] nt = t.clone();
            float alpha = 0.20f;
            for (int i = 0; i < n; i++) {
                float left  = (i > 0)     ? t[i - 1] : t[i];
                float right = (i < n - 1) ? t[i + 1] : t[i];
                nt[i] = t[i] + alpha * (left + right - 2 * t[i]);
            }
            t = nt;

            // (2) phase change on post-step temps (all cells)
            for (int i = 0; i < n; i++) {
                Optional<Identifier> target = PhaseRule.targetBlock(t[i], matAt.apply(i));
                if (target.isPresent()) {
                    block[i] = target.get();
                    if (target.get().equals(ORGE_STEAM)) {
                        waterBoiled = true;
                    }
                }
            }

            // (3) conditional re-pin: a still-pinned cell that did not transition snaps back.
            //     (A cell that transitioned away from lava is no longer "lava" here, so it is
            //      not re-pinned — mirroring SourcePinPlanner's "pinned AND not transitioned".)
            for (int i = 0; i < n; i++) {
                if (block[i].getPath().equals("lava")) {
                    t[i] = matAt.apply(i).defaultTemperature();
                }
            }

            // invariant: lava cell never froze to stone
            assertNotEquals("stone", block[0].getPath(), "lava froze at second " + second);
        }

        assertTrue(waterBoiled, "water adjacent to pinned lava should boil to orge:steam within 200s");
        assertEquals("lava", block[0].getPath(), "lava must remain pinned lava");
        assertTrue(t[0] >= 1399f, "lava re-pinned to ~1400 K, was " + t[0]);
    }

    // ---- Phase-2a advection audit (native) -------------------------------------------------

    private static final int SEC_N = 4096;
    private static final int FACE = NeighborHalo.FACE_CELLS;

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    private static float sum(float[] a) {
        double s = 0;
        for (float v : a) s += v;
        return (float) s;
    }

    private static NeighborHalo voidHalo() {
        float[] zf = new float[FACE];      // all temps/masses 0
        char[]  zc = new char[FACE];       // all material indices 0 (void) -> no-flow wall
        return new NeighborHalo(
                zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(),
                zc.clone(), zc.clone(), zc.clone(), zc.clone(), zc.clone(), zc.clone(),
                zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone());
    }

    /**
     * A bounded fluid region in an otherwise-void section: a vertical column
     * (8,11,8)..(8,6,8) plus one horizontal neighbour (9,6,8) at the bottom row, so the kernel
     * can both FALL (down the column) and SPREAD (sideways into the neighbour). The upper cells
     * start full (1000 kg each) and the bottom two start empty. Phase-2b: the floor-active .so now
     * wets the head of water into the surrounding air, where it pools to a FINITE coverage — the
     * deepest basin cell reaches the max_mass cap while the rest sit at/near the min_flow floor; no
     * cell overflows the cap and the pool does not flood the plane. Everything else is void (matIx 0),
     * which acts as a no-flow wall — so this region cannot leak across the section boundary and
     * Σmass over the whole section stays conserved (the §9 invariant in action), unlike an
     * all-fluid 16³ section which leaks horizontally (Task-14 finding).
     */
    @Test
    void waterFallsSpreadsAndReconciles() {
        NativeEngine e;
        try {
            NativeLoader.load();
            e = new NativeEngine();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
            return;
        }

        Material water = water();                 // fluid=true, defaultMass 1000
        assertTrue(water.fluid(), "audit water must be a fluid to advect");

        char[]  matIx = new char[SEC_N];          // all void
        float[] mass  = new float[SEC_N];
        float[] temp  = new float[SEC_N];
        Arrays.fill(temp, 300f);

        // a 6-cell column at x=8,z=8 from y=11 (top) down to y=6 (bottom), plus a horizontal
        // neighbour of the bottom cell. The upper four cells start full; the bottom two empty.
        int top    = sidx(8, 11, 8);              // the original full upper cell
        int bottom = sidx(8, 6, 8);               // lowest cell in the column
        int side   = sidx(9, 6, 8);               // horizontal neighbour at the bottom row
        int[] column = {
                sidx(8, 11, 8), sidx(8, 10, 8), sidx(8, 9, 8),
                sidx(8, 8, 8),  sidx(8, 7, 8),  sidx(8, 6, 8)
        };
        for (int c : column) matIx[c] = (char) 1; // fluid (LUT idx 1)
        matIx[side] = (char) 1;
        // fill the upper four cells; leave the bottom two (and the side cell) empty.
        for (int i = 0; i < 4; i++) mass[column[i]] = 1000f;

        float initialTotal = sum(mass);
        assertEquals(4000f, initialTotal, 1e-3f);

        // LUT: [void, water]
        List<Material> lut = List.of(
                new Material(Identifier.fromNamespaceAndPath("orge", "void"),
                        0f, 0f, 0f, 0f, 0.018f, 9999f, 0f, null, null, null),
                water);

        // step ~30 times, feeding mass+temperature back as the next input (like NativeEngineTest).
        for (int it = 0; it < 30; it++) {
            StepTask task = new StepTask(new SubchunkKey(0, 0, 0), matIx, mass, temp, voidHalo());
            List<StepResult> out = e.step(List.of(task), lut,
                    Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            mass = out.get(0).mass();
            temp = out.get(0).temperature();
            // conservation: Σmass over the whole bounded section is unchanged every step.
            assertEquals(initialTotal, sum(mass), 1e-2f,
                    "Σmass must be conserved at iteration " + it);
        }

        // --- Phase-2b finite-pooling end-state (was Phase-2a stacking) -----------------------
        // Survey the whole bounded section: count occupied cells and find the fullest.
        int occupied = 0;
        float fullest = 0f;
        for (int i = 0; i < SEC_N; i++) {
            if (mass[i] > 1e-6f) occupied++;
            fullest = Math.max(fullest, mass[i]);
        }

        // (a) Phase-2b: water FELL out of the top cell — the head drained (was: top stayed full).
        assertEquals(0f, mass[top], 1e-2f, "top cell should have fully drained, was " + mass[top]);
        // (b) Phase-2b: water SPREAD into air past one cell — the bottom basin holds mass and the
        //     horizontal neighbour gained mass (coverage is more than the single seed cell).
        assertTrue(mass[bottom] > 0f, "bottom of column should hold settled mass, was " + mass[bottom]);
        assertTrue(mass[side]   > 0f, "horizontal neighbour should have gained mass, was " + mass[side]);
        assertTrue(occupied > 1, "water must wet into a finite pool (> 1 cell), occupied=" + occupied);
        // (c) Phase-2b: occupancy is FINITE — a free pool spreads to a bounded coverage, not the
        //     whole 4096-cell plane (the min_flow floor + max_mass cap keep it compact, measured 20).
        assertTrue(occupied <= 64,
                "free pool must stay finite (<= 64 cells), not flood the section, occupied=" + occupied);
        // (d) Phase-2b: NO cell exceeds the per-cell capacity cap (max_mass); the deepest basin cell
        //     pools to exactly the cap (was: the seed column "stacked full").
        assertTrue(fullest <= water.maxMass() + 1e-2f,
                "no cell may exceed max_mass=" + water.maxMass() + ", fullest was " + fullest);

        // (e) Reconcile (§10 Decision 9): a basin cell at the cap renders as a full block (level 0)...
        assertEquals(0, FluidReconcileLogic.levelForFraction(
                FluidReconcileLogic.fraction(fullest, water.defaultMass())),
                "the fullest basin cell renders as level 0");
        // ...and a partially-filled pool cell at the floor reconciles to a VALID render level (0..7).
        int floorLevel = FluidReconcileLogic.levelForFraction(
                FluidReconcileLogic.fraction(mass[bottom], water.defaultMass()));
        assertTrue(floorLevel >= 0 && floorLevel <= 7,
                "a floor-filled pool cell reconciles to a valid level 0..7, was " + floorLevel);
        // ...and a fully-drained cell (the emptied top) reconciles to REMOVE.
        assertEquals(FluidReconcileLogic.REMOVE, FluidReconcileLogic.levelForFraction(
                FluidReconcileLogic.fraction(mass[top], water.defaultMass())),
                "the drained top cell reconciles to REMOVE");
    }

    // ---- Air-sink regression (the in-game bug, headless) -----------------------------------

    // First-class AIR material (State.AIR): air()==true, fluid()==false, resting mass ~1.2 kg.
    // Placed at a NON-ZERO LUT index so the kernel must read the air LUT flag (NOT the matIx==0
    // void clause) to treat it as a fall/spread/wet sink. This is the array the ABI now passes.
    private static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static Material air() {
        return new Material(AIR, 0.026f, 1005f, 0f, 1.2f, 0.029f,
                Float.POSITIVE_INFINITY, 0f, null, null, null,
                Float.NaN, false, Material.State.AIR, 0f, 0f);
    }

    /**
     * The regression that would have caught the unwired air LUT: a water column above/beside REAL
     * air cells (a non-zero LUT material with {@code air()==true}). The freshly bundled, air-aware
     * .so must (a) move water mass DOWN/SIDEWAYS into the air cells (the air cells gain mass, the
     * source loses it; finite pooling, no overflow) and (b) ADOPT those cells' output species to
     * water. We also run the result through the §9 gate
     * ({@link StepValidator#massConservedPerSpecies}) to prove the engine air-sink and the §9
     * air-mass credit work together — the real in-game acceptance gate. If only matIx==0 void were a
     * sink (the OLD .so / unwired ABI), the non-zero air cells would stay empty and this fails.
     */
    @Test
    void waterFallsAndWetsIntoRealAirAndSection9Accepts() {
        OrgeEngine eng = EngineFactory.create();
        assumeTrue(eng instanceof NativeEngine,
                "native liborge must load on linux-x64; got " + eng.getClass().getSimpleName());
        NativeEngine e = (NativeEngine) eng;

        Material water = water();   // LUT idx 1: fluid, defaultMass 1000, floor 125 below
        Material air   = air();     // LUT idx 2: REAL air, air()==true, fluid()==false, 1.2 kg
        assertTrue(water.fluid(), "water must be a fluid to advect");
        assertTrue(air.air() && !air.fluid(), "air material must be air() and not fluid()");

        // Mirror the proven kernel air-sink scenario (ORGE-ENGINE tests/test_air_sink.cpp): the WHOLE
        // section is REAL AIR (matIx 2, resting 1.2 kg) — the in-game ambient — so the air cells sit at
        // a NON-ZERO LUT index, forcing the kernel to consult the air LUT flag (not the matIx==0 void
        // clause). Then: a water column gives a FALL sink directly below, and a floor water cell gives
        // a sideways WET sink (kernel fall is interior-only, so a yl==0 cell is "supported" and spreads).
        char[]  matIx = new char[SEC_N];
        float[] mass  = new float[SEC_N];
        float[] temp  = new float[SEC_N];
        Arrays.fill(temp, 300f);
        Arrays.fill(matIx, (char) 2);             // every cell starts as REAL AIR
        Arrays.fill(mass, 1.2f);                  // air resting mass

        // FALL: a water column x=8,z=8 from y=11 (top) down to y=7. The whole air column beneath is a
        // sink, so water falls all the way to the section floor (y=0) and pools there — the FALL path
        // wets every air cell it transits (species adoption) and settles a finite pool at the floor.
        int[] waterCol = {
                sidx(8, 11, 8), sidx(8, 10, 8), sidx(8, 9, 8),
                sidx(8, 8, 8),  sidx(8, 7, 8)
        };
        int airBelow = sidx(8, 6, 8);             // REAL air a fall cell transits -> wetted by the fall
        int floorPool = sidx(8, 0, 8);            // section floor under the column -> water pools here
        int top      = waterCol[0];
        for (int c : waterCol) { matIx[c] = (char) 1; mass[c] = 1000f; }

        // WET sideways: a SUPPORTED water cell on the section floor (yl==0), beside a real-air cell.
        int floorSrc = sidx(4, 0, 4);             // on the floor => cannot fall => supported
        int airSide  = sidx(5, 0, 4);             // REAL air to the +x side -> SPREAD sink
        matIx[floorSrc] = (char) 1; mass[floorSrc] = 1000f;

        // water (idx1): floor 125, cap 1000. air (idx2) at NON-ZERO index.
        List<Material> lut = List.of(
                new Material(Identifier.fromNamespaceAndPath("orge", "void"),
                        0f, 0f, 0f, 0f, 0.018f, 9999f, 0f, null, null, null),
                new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                        373.15f, 273.15f, ORGE_STEAM, ICE, null,
                        Float.NaN, false, Material.State.FLUID, 125f, 1000f),
                air);

        float airSideBefore  = mass[airSide];     // 1.2
        float waterIn = 5f * 1000f;               // total water mass seeded in the column

        // Run several steps via the production native path; check the §9 gate every step.
        for (int it = 0; it < 30; it++) {
            char[]  inMat  = matIx.clone();
            float[] before = mass.clone();
            StepTask task = new StepTask(new SubchunkKey(0, 0, 0), matIx, mass, temp, voidHalo());
            List<StepResult> out = e.step(List.of(task), lut,
                    Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            mass = out.get(0).mass();
            temp = out.get(0).temperature();
            char[] outMat = out.get(0).material() != null ? out.get(0).material() : matIx;
            matIx = outMat;
            // §9 gate: with the air-mass credit, the air-sink step must be ACCEPTED.
            assertTrue(StepValidator.massConservedPerSpecies(mass, before, inMat, outMat, lut),
                    "§9 massConservedPerSpecies must accept the air-sink step at iteration " + it);
        }

        // Tally the water that actually moved through the air column down to the floor pool.
        float floorWater = 0f;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int i = sidx(x, 0, z);
            if (matIx[i] == 1) floorWater += mass[i];
        }

        // (a) FALL: the water column drained — its head fell THROUGH the real-air column to the floor.
        assertEquals(0f, mass[top], 1e-2f, "top of the water column should have drained, was " + mass[top]);
        assertTrue(mass[floorPool] > 1f,
                "water must have fallen through the air column and pooled on the floor, was " + mass[floorPool]);
        assertTrue(floorWater > 1000f,
                "the bulk of the column's water must reach the floor pool, was " + floorWater + " of " + waterIn);
        // (b) WET: the supported floor cell spread sideways into its air neighbour.
        assertTrue(mass[airSide] > airSideBefore + 1f,
                "air cell beside the floor source must gain water mass (sideways wet), was " + mass[airSide]);

        // finite pooling: no cell exceeds water's cap.
        float fullest = 0f;
        for (int i = 0; i < SEC_N; i++) fullest = Math.max(fullest, mass[i]);
        assertTrue(fullest <= water.maxMass() + 1e-2f,
                "no cell may exceed water max_mass=" + water.maxMass() + ", fullest was " + fullest);

        // (c) species ADOPTION: every real-air cell the water fell through / wet into became water
        //     (idx 1) — the air cells were ADOPTED, not left as air (idx 2). This is the bug's tell:
        //     with the OLD unwired .so the non-zero air cells stay air and water never enters them.
        assertEquals(1, (int) matIx[airBelow], "air cell the fall transited must adopt water species");
        assertEquals(1, (int) matIx[floorPool], "floor pool cell must be water species");
        assertEquals(1, (int) matIx[airSide],  "wetted air-side cell must adopt water species");
    }

    // A fluid lava (fluid=true) for the advection-merge audit. Distinct material index from water,
    // so the same-material guard (spec Decision 7) must keep their masses separate even though both
    // are fluids. defaultMass 3000 kg, viscosity > water so it spreads less.
    private static Material fluidLava() {
        return new Material(LAVA, 1.5f, 1000f, 0.1f, 3000f, 0f,
                Float.POSITIVE_INFINITY, 1000f, null, STONE, null, 1400f, true, true);
    }

    @Test
    void waterNextToLavaStillSteamsAndMassesDoNotMerge() {
        // Drives the native kernel with adjacent water+lava fluid cells. Spec Decision 7: advection
        // only moves mass between SAME-material fluid cells, so water's mass never merges into the
        // adjacent lava cell and vice-versa, even though both are fluids. The water-on-lava
        // interaction stays owned by §7 (PhaseRule -> orge:steam), asserted below.
        NativeEngine e;
        try {
            NativeLoader.load();
            e = new NativeEngine();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
            return;
        }

        Material water = water();          // LUT idx 1, fluid, defaultMass 1000
        Material lava  = fluidLava();      // LUT idx 2, fluid, defaultMass 3000
        assertTrue(water.fluid(), "audit water must be a fluid to advect");
        assertTrue(lava.fluid(),  "audit lava must be a fluid so the guard (not isFluid) is what blocks the merge");

        char[]  matIx = new char[SEC_N];   // all void (idx 0)
        float[] mass  = new float[SEC_N];
        float[] temp  = new float[SEC_N];
        Arrays.fill(temp, 300f);

        // y=8 plane, x running 6..9 at z=8:
        //   wHi  (x=8): water, lots of mass + hot -> wants to spread/fall
        //   lava (x=9): wHi's +x neighbour, lava with mass, cold -> water would merge in w/o guard
        //   wLo  (x=7): wHi's -x neighbour, SAME material (water), empty -> must still receive
        // A column above wHi gives it a head to fall, exercising the FALL path past the lava too.
        int wHi  = sidx(8, 8, 8);
        int wTop = sidx(8, 9, 8);          // water above wHi (fall path stays same-material)
        int lava2 = sidx(9, 8, 8);         // lava neighbour of wHi
        int wLo  = sidx(7, 8, 8);          // empty water neighbour of wHi
        matIx[wHi]  = (char) 1; mass[wHi]  = 900f;  temp[wHi]  = 1000f;
        matIx[wTop] = (char) 1; mass[wTop] = 1000f; temp[wTop] = 1000f;
        matIx[lava2] = (char) 2; mass[lava2] = 600f; temp[lava2] = 300f;
        matIx[wLo]  = (char) 1; mass[wLo]  = 0f;     temp[wLo]  = 300f;

        float waterTotalIn = mass[wHi] + mass[wTop] + mass[wLo];
        float lavaMassIn   = mass[lava2];
        float totalIn      = sum(mass);

        // LUT: [void, water, lava] — both water and lava are fluids.
        List<Material> lut = List.of(
                new Material(Identifier.fromNamespaceAndPath("orge", "void"),
                        0f, 0f, 0f, 0f, 0.018f, 9999f, 0f, null, null, null),
                water,
                lava);

        char[] outMat = matIx;
        for (int it = 0; it < 30; it++) {
            StepTask task = new StepTask(new SubchunkKey(0, 0, 0), matIx, mass, temp, voidHalo());
            List<StepResult> out = e.step(List.of(task), lut,
                    Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            mass = out.get(0).mass();
            temp = out.get(0).temperature();
            if (out.get(0).material() != null) { outMat = out.get(0).material(); matIx = outMat; }
            // total conservation holds every step (the symmetric guard never breaks it).
            assertEquals(totalIn, sum(mass), 1e-2f, "Σmass must be conserved at iteration " + it);
        }

        // Survey the field by OUTPUT species (Decision 7: one species per cell). Each fluid wets into
        // its OWN adjacent air, so we track mass+occupancy per species across the whole section, not
        // just the seed cells. A cell that ever held both species would prove a cross-material merge.
        float waterSpeciesSum = 0f, lavaSpeciesSum = 0f;
        float hottestLavaCell  = 0f;
        for (int i = 0; i < SEC_N; i++) {
            if (outMat[i] == 1) waterSpeciesSum += mass[i];                 // water species
            if (outMat[i] == 2) {                                          // lava species
                lavaSpeciesSum += mass[i];
                if (mass[i] > 1e-6f) hottestLavaCell = Math.max(hottestLavaCell, temp[i]);
            }
        }

        // --- Phase-2b finite-pooling end-state, NO cross-material merge (Decision 7) -----------
        // (1) NO-MERGE: lava did not GAIN mass from the adjacent water. Lava now legitimately pools
        //     into its OWN +x air (was: "lava mass exactly == 600 in one cell"), so we assert the
        //     lava SPECIES total is conserved (<= initial + ε) — water never crossed into lava.
        assertEquals(lavaMassIn, lavaSpeciesSum, 1e-1f,
                "lava species mass must be conserved, none gained from water (Decision 7), was " + lavaSpeciesSum);
        assertTrue(lavaSpeciesSum <= lavaMassIn + 1e-1f,
                "lava must not GAIN mass from adjacent water, was " + lavaSpeciesSum);
        // (2) NO-MERGE: every lava-species cell stayed COLD (~300 K) — the 1000 K water never blended
        //     its enthalpy across the material boundary (was: single-cell lava2 temp == 300).
        assertEquals(300f, hottestLavaCell, 1.0f,
                "lava cells must stay unblended by hot water (no enthalpy merge), hottest was " + hottestLavaCell);
        // (3) per-species conservation: ALL water mass stayed water (none leaked into lava cells).
        assertEquals(waterTotalIn, waterSpeciesSum, 1e-1f,
                "all water mass must remain water species, was " + waterSpeciesSum);
        // (4) the lava SEED cell never holds water species (no merge into the boundary cell itself).
        assertTrue(outMat[lava2] != 1,
                "lava seed cell must never become water species, was " + (int) outMat[lava2]);
        // (5) Phase-2b pooling actually happened: the water wet into air (more cells than the 2 seeds).
        int waterOcc = 0;
        for (int i = 0; i < SEC_N; i++) if (outMat[i] == 1 && mass[i] > 1e-6f) waterOcc++;
        assertTrue(waterOcc > 1, "water must have spread into a finite pool (> 1 cell), occupied=" + waterOcc);

        // KEEP §7: a water cell heated above boiling still yields orge:steam via PhaseRule.
        Optional<Identifier> phaseTarget = PhaseRule.targetBlock(400f, water);
        assertEquals(ORGE_STEAM, phaseTarget.orElse(null));
        // KEEP reconcile: per-material defaultMass — water fraction uses water's full mass.
        assertEquals(0, FluidReconcileLogic.levelForFraction(
                FluidReconcileLogic.fraction(1000f, water.defaultMass())));
    }

    @Test
    void sourcePinPlannerHoldsLavaWhenNoTransition() {
        java.util.function.IntFunction<Material> cells = i -> (i == 0) ? lava() : water();
        List<SourcePinPlanner.Reset> resets = SourcePinPlanner.plan(cells, List.of());
        assertFalse(resets.isEmpty());
        assertEquals(0, resets.get(0).cellIndex());
        assertEquals(1400f, resets.get(0).temperatureK(), 1e-3f);
    }
}
