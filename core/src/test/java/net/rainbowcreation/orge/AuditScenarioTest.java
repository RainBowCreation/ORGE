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
import org.junit.jupiter.api.Disabled;
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
        assertTrue(water.movable(), "audit water must be a fluid to advect");

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

    // air material: a movable finite gas (~1.2 kg per cell). Under the canonical schema there is no
    // separate AIR state — finite viscosity makes it movable, the sink fluids displace.
    private static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static Material air() {
        return Material.builder(AIR)
                .thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.029f)
                .defaultMass(1.2f).defaultTemperature(Float.NaN)
                .viscosity(0f).minTemp(0f)
                .build();
    }

    /**
     * The regression that would have caught the unwired air LUT: a water column above/beside REAL
     * air cells (a non-zero LUT material with {@code air()==true}). The freshly bundled, air-aware
     * .so must (a) move water mass DOWN/SIDEWAYS into the air cells (the air cells gain mass, the
     * source loses it; finite pooling, no overflow) and (b) settle that water into the destination
     * cells WITHOUT leaving a residue in cells it merely passed through (the Bug-A swap, not absorb).
     * We also run the result through the §9 gate
     * ({@link StepValidator#massConservedPerSpecies}) to prove the engine air-sink and the §9
     * air-mass credit work together — the real in-game acceptance gate. If only matIx==0 void were a
     * sink (the OLD .so / unwired ABI), the non-zero air cells would stay empty and this fails.
     */
    @Test
    @Disabled("Task 1.1: the canonical schema drops the air()/fluid() flag distinction, so the interim "
            + "LUT packs air=0 and the kernel's air-DISPLACEMENT sink is inactive. Water-falls-through-real-"
            + "air is reintroduced via the molar-mass-sorted advection in Task 3.x — re-enable then.")
    void waterFallsAndWetsIntoRealAirAndSection9Accepts() {
        OrgeEngine eng = EngineFactory.create();
        assumeTrue(eng instanceof NativeEngine,
                "native liborge must load on linux-x64; got " + eng.getClass().getSimpleName());
        NativeEngine e = (NativeEngine) eng;

        Material water = water();   // LUT idx 1: fluid, defaultMass 1000, floor 125 below
        Material air   = air();     // LUT idx 2: REAL air, air()==true, fluid()==false, 1.2 kg
        assertTrue(water.movable(), "water must be a fluid to advect");
        assertTrue(air.movable(), "air material must be a movable gas");

        // Mirror the proven kernel air-sink scenario (ORGE-ENGINE tests/test_air_sink.cpp): the WHOLE
        // section is REAL AIR (matIx 2, resting 1.2 kg) — the in-game ambient — so the air cells sit at
        // a NON-ZERO LUT index, forcing the kernel to consult the air LUT flag (not the matIx==0 void
        // clause). Then: a water column gives a FALL sink directly below, and a floor water cell gives
        // a sideways WET sink (kernel fall is interior-only, so a yl==0 cell is "supported" and spreads).
        char[]  matIx = new char[SEC_N];
        float[] mass  = new float[SEC_N];
        float[] temp  = new float[SEC_N];
        Arrays.fill(temp, 300f);

        // §11 conserve-air: the air the falling/wetting water DISPLACES must stay a TRACKED species and
        // must NOT cross the section face (the single-section §9 gate cannot credit cross-face transfers).
        // So we hold the active region INSIDE an interior chamber: inert STONE SIDE WALLS (x,z = 2/13),
        // a stone end-cap nowhere above air (no solid ceiling — a solid directly above air would trigger
        // the gas-buoyancy swap and let air lift the solid), and VACUUM headroom (within-section void at
        // y >= 11) above the air column so displaced air expands UP into vacuum (E3, conserved, interior)
        // instead of reaching the y=15 face. The SECTION FLOOR (y=0) is left AIR so a floor water cell
        // sits at yl==0 — the kernel's "supported" predicate that lets it spread sideways.
        for (int x = 2; x <= 13; x++) for (int y = 0; y <= 10; y++) for (int zz = 2; zz <= 13; zz++) {
            boolean wall = (x == 2 || x == 13 || zz == 2 || zz == 13);    // side walls only; y=0 + top open
            int i = sidx(x, y, zz);
            matIx[i] = wall ? (char) 3 : (char) 2;         // 3=STONE side wall, 2=AIR interior (incl. y=0 floor)
            mass[i]  = wall ? 2000f : 1.2f;
        }
        // (cells at y >= 11 stay VOID/vacuum (index 0, mass 0) — the headroom the displaced air expands into)

        // FALL: a single FULL water cell at the TOP of the air column (x=8,z=8,y=10). The air column
        // beneath (down to the section floor y=0) is a fall sink, so the water SINKS by density swap to the
        // floor and pools — each step it drops one cell while the displaced air RISES (into the next air
        // cell, ultimately expanding into the vacuum headroom), conserved, no residue. A single falling
        // cell keeps each step a clean 1:1 swap the single-section §9 gate validates (a multi-cell column
        // collapse is a co-stepped/batch concern, proven separately by the cross-section + Section11 E2Es).
        int top      = sidx(8, 10, 8);
        int airBelow = sidx(8, 6, 8);             // REAL air the falling cell transits -> swapped through
        int floorPool = sidx(8, 0, 8);            // section floor under the column -> water pools here
        matIx[top] = (char) 1; mass[top] = 1000f;

        // (Sideways WETTING into real air — supported water DISPLACES the side-air upward, total water
        // stays exactly 1000.0, air conserved — is proven on the rebuilt lib by
        // Section11PhaseANativeE2ETest.waterSpreadAcrossNCellsTotalsExactly1000AndAirIsConserved and in
        // the ENGINE's own tests/test_gas_displace.cpp headline. THIS test focuses on the orthogonal
        // FALL-through-air + no-residue + per-step §9 contract that the two share a section is awkward to
        // co-assert without the fall and wet fluxes interfering under the strict single-section gate.)

        // water (idx1): floor 125, cap 1000. air (idx2) at NON-ZERO index. stone (idx3): inert wall.
        List<Material> lut = List.of(
                new Material(Identifier.fromNamespaceAndPath("orge", "void"),
                        0f, 0f, 0f, 0f, 0.018f, 9999f, 0f, null, null, null),
                new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                        373.15f, 273.15f, ORGE_STEAM, ICE, null,
                        Float.NaN, false, true, 125f, 1000f, false),
                air,
                new Material(STONE, 1.0f, 840f, 0f, 2000f, 0f, 9999f, 0f, null, null, null));

        float waterIn = 1000f;                    // the single falling cell
        float airTotalBefore = 0f;                // §11: air must be CONSERVED (displaced, not consumed)
        for (int i = 0; i < SEC_N; i++) if (matIx[i] == 2) airTotalBefore += mass[i];

        // Run several steps via the production native path; check the §9 gate every step.
        for (int it = 0; it < 60; it++) {
            char[]  inMat  = matIx.clone();
            float[] before = mass.clone();
            StepTask task = new StepTask(new SubchunkKey(0, 0, 0), matIx, mass, temp, voidHalo());
            List<StepResult> out = e.step(List.of(task), lut,
                    Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            mass = out.get(0).mass();
            temp = out.get(0).temperature();
            char[] outMat = out.get(0).material() != null ? out.get(0).material() : matIx;
            matIx = outMat;
            // §9 gate: with air as a conserved species, the displacement step must be ACCEPTED.
            assertTrue(StepValidator.massConservedPerSpecies(mass, before, inMat, outMat, lut),
                    "§9 massConservedPerSpecies must accept the air-sink step at iteration " + it);
        }

        // Tally the water that actually moved through the air column down to the section floor (y=0).
        float floorWater = 0f;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int i = sidx(x, 0, z);
            if (matIx[i] == 1) floorWater += mass[i];
        }

        // (a) FALL: the falling cell drained from the top — it sank THROUGH the real-air column.
        assertTrue(mass[top] <= 1.2f + 1e-2f,
                "top of the water column should have drained (risen air, ~1.2 kg), was " + mass[top]);
        assertTrue(mass[floorPool] > 1f,
                "water must have fallen through the air column and pooled on the floor, was " + mass[floorPool]);
        assertTrue(floorWater > 900f,
                "the falling cell's water must reach the floor pool, was " + floorWater + " of " + waterIn);
        // (a') §11 air CONSERVED: the displaced air is relocated (risen), never consumed.
        float airTotalAfter = 0f;
        for (int i = 0; i < SEC_N; i++) if (matIx[i] == 2) airTotalAfter += mass[i];
        assertEquals(airTotalBefore, airTotalAfter, Math.max(1f, airTotalBefore * 1e-3f),
                "air must be CONSERVED (displaced, never consumed), was " + airTotalAfter + " vs " + airTotalBefore);
        // finite pooling: no WATER cell exceeds water's cap (the stone walls hold 2000 kg by design).
        float fullest = 0f;
        for (int i = 0; i < SEC_N; i++) if (matIx[i] == 1) fullest = Math.max(fullest, mass[i]);
        assertTrue(fullest <= water.maxMass() + 1e-2f,
                "no water cell may exceed water max_mass=" + water.maxMass() + ", fullest was " + fullest);

        // (c) SWAP (Bug A): with the displacement swap the water leaves NO WATER residue in the cells it
        //     merely passed THROUGH — a transited cell holds the RISEN AIR (~1.2 kg, air species), not a
        //     relabelled water residue. (The OLD absorb left a 1.2 kg WATER residue that cascaded
        //     1.2 -> 2.4 -> ...; the displacement swap restores clean air.)
        assertEquals(2, (int) matIx[airBelow],
                "a transited cell must hold the risen AIR (no water residue), was mat " + (int) matIx[airBelow]);
        assertEquals(1.2f, mass[airBelow], 1e-2f,
                "a transited cell must carry only the air's ~1.2 kg (no accumulating water residue), was " + mass[airBelow]);
        // the destination the water settled into IS water species and DOES carry mass.
        assertEquals(1, (int) matIx[floorPool], "floor pool cell must be water species");
        assertTrue(mass[floorPool] > 1f, "floor pool cell must carry settled water mass, was " + mass[floorPool]);
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
        assertTrue(water.movable(), "audit water must be a fluid to advect");
        assertTrue(lava.movable(),  "audit lava must be a fluid so the guard (not isFluid) is what blocks the merge");

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

    /**
     * Bug A end-to-end on the production .so: a water cell sitting on top of a column of REAL air
     * (a non-zero LUT material, {@code air()==true}, resting ~1.2 kg) must SINK by full-cell SWAP —
     * the water drops one cell per advection step while the displaced air's ~1.2 kg RISES into the
     * vacated cell. The decisive Bug-A tell is the column AFTER the water has passed: every transited
     * cell must be AIR again (species air, ~1.2 kg) with <b>NO ~1.2 kg water residue and NO
     * accumulating residue trail</b> — the OLD absorb kept a relabelled 1.2 kg in the donor that
     * cascaded 1.2 -> 2.4 -> 3.6. We seal the shaft with solid stone walls (idx 3, not fluid/air/void)
     * so the sunk water collects in a single full ~1000 kg floor cell instead of leveling sideways.
     * Mass is checked total-conserved every step and §9 {@link StepValidator#massConservedPerSpecies}
     * must return TRUE every step (the air-mass credit accepting the swap).
     */
    @Test
    @Disabled("Task 1.1: the canonical schema drops the air()/fluid() flag distinction, so the interim "
            + "LUT packs air=0 and the kernel's air-DISPLACEMENT swap is inactive. The water-sinks-through-"
            + "real-air (no-residue) swap is reintroduced via the molar-mass-sorted advection in Task 3.x.")
    void waterSinksThroughRealAirColumnDisplacingAirNoResidueAndSection9Accepts() {
        OrgeEngine eng = EngineFactory.create();
        assumeTrue(eng instanceof NativeEngine,
                "native liborge must load on linux-x64; got " + eng.getClass().getSimpleName());
        NativeEngine e = (NativeEngine) eng;

        Material water = water();   // LUT idx 1
        Material air   = air();     // LUT idx 2: REAL air, air()==true, fluid()==false, 1.2 kg
        assertTrue(water.movable(), "water must be a fluid to advect");
        assertTrue(air.movable(), "air material must be a movable gas");

        char[]  matIx = new char[SEC_N];   // mostly void (idx 0)
        float[] mass  = new float[SEC_N];
        float[] temp  = new float[SEC_N];
        Arrays.fill(temp, 300f);

        // A narrow vertical shaft at x=8,z=8: REAL air (idx 2, 1.2 kg) at y=0..9 and ONE full water
        // cell (1000 kg) on top at y=10. The air sits at a NON-ZERO LUT index so the kernel must
        // consult the air LUT flag (not the matIx==0 void clause) to swap into it.
        int AIRTOP = 9;            // highest real-air cell (water starts directly above it)
        int WY     = 10;           // the falling water cell
        for (int y = 0; y <= AIRTOP; y++) { int i = sidx(8, y, 8); matIx[i] = (char) 2; mass[i] = 1.2f; }
        int wcell = sidx(8, WY, 8); matIx[wcell] = (char) 1; mass[wcell] = 1000f;

        // Solid stone walls (idx 3: fluid()==false, air()==false -> a no-flow wall for spread) around
        // the shaft so the sunk water cannot level sideways and instead fills a single full floor cell.
        for (int y = 0; y <= WY; y++) {
            for (int wall : new int[]{ sidx(7, y, 8), sidx(9, y, 8), sidx(8, y, 7), sidx(8, y, 9) }) {
                matIx[wall] = (char) 3; mass[wall] = 2000f;
            }
        }
        int floor = sidx(8, 0, 8);     // where the water must end up (full ~1000 kg)

        List<Material> lut = List.of(
                new Material(Identifier.fromNamespaceAndPath("orge", "void"),
                        0f, 0f, 0f, 0f, 0.018f, 9999f, 0f, null, null, null),
                new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                        373.15f, 273.15f, ORGE_STEAM, ICE, null,
                        Float.NaN, false, true, 125f, 1000f, false),
                air,
                new Material(STONE, 1.0f, 840f, 0f, 2000f, 0f, 9999f, 0f, null, null, null));

        final int K = 30;                       // advection steps (>= the 10-cell drop depth)
        final float totalBefore = sum(mass);    // 1000 water + 10*1.2 air + 44 walls*2000

        for (int it = 0; it < K; it++) {
            char[]  inMat  = matIx.clone();
            float[] before = mass.clone();
            StepTask task = new StepTask(new SubchunkKey(0, 0, 0), matIx, mass, temp, voidHalo());
            List<StepResult> out = e.step(List.of(task), lut,
                    Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            mass = out.get(0).mass();
            temp = out.get(0).temperature();
            char[] outMat = out.get(0).material() != null ? out.get(0).material() : matIx;
            matIx = outMat;
            // total mass conserved every step
            assertEquals(totalBefore, sum(mass), 1e-1f,
                    "total mass must be conserved at step " + it);
            // §9 accepts the swap (air-mass credit) every step — NOT weakened.
            assertTrue(StepValidator.massConservedPerSpecies(mass, before, inMat, outMat, lut),
                    "§9 massConservedPerSpecies must accept the air-displacement swap at step " + it);
        }

        // The water SANK to a single full cell at the floor (~1000 kg, species water).
        assertEquals(1, (int) matIx[floor], "floor cell must be water species after sinking");
        assertEquals(1000f, mass[floor], 1e-1f,
                "the water must collect as a single full ~1000 kg cell at the floor, was " + mass[floor]);
        // the original top water cell drained of water and is now occupied by the RISEN air (not 0,
        // not water): the swap moved water down and air up, conserving the cell's occupancy.
        assertEquals(2, (int) matIx[wcell],
                "the original top water cell must now hold risen air, was mat " + (int) matIx[wcell]);

        // Bug-A TELL: every cell the water passed through is AIR again (species 2, ~1.2 kg) — the air
        // rose into the vacated cells. NO ~1.2 kg water residue, NO accumulating residue trail.
        for (int y = 1; y <= WY; y++) {     // y=0 holds the water; y=1..10 must be the risen air
            int i = sidx(8, y, 8);
            assertEquals(2, (int) matIx[i],
                    "transited cell y=" + y + " must be AIR again (displaced, no water residue), was mat " + (int) matIx[i]);
            assertEquals(1.2f, mass[i], 1e-2f,
                    "transited cell y=" + y + " must hold the air's ~1.2 kg (no accumulating residue), was " + mass[i]);
        }

        // and the whole section holds exactly one water cell of exactly 1000 kg (no residue anywhere).
        float waterTotal = 0f; int waterCells = 0;
        for (int i = 0; i < SEC_N; i++) if (matIx[i] == 1) { waterTotal += mass[i]; waterCells++; }
        assertEquals(1, waterCells, "exactly one water cell may remain (no residue trail)");
        assertEquals(1000f, waterTotal, 1e-1f, "all 1000 kg of water is conserved in that one cell");
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
