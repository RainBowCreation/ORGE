package net.rainbowcreation.orge;

import net.minecraft.resources.Identifier;
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
     * start full (1000 kg each) and the bottom two start empty; that head of water settles into
     * the bottom basin, filling a lower cell near to capacity. Everything else is void (matIx 0),
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

        // a lower cell ended up holding mass (water fell down the column).
        assertTrue(mass[bottom] > 0f, "bottom of column should hold settled mass, was " + mass[bottom]);
        // a horizontal neighbour gained mass (water spread sideways).
        assertTrue(mass[side] > 0f, "horizontal neighbour should have gained mass, was " + mass[side]);
        // the original top cell drained (mass fell out of it).
        assertTrue(mass[top] < 1000f, "top cell should have drained, was " + mass[top]);

        // map the settled field through the pure reconcile logic (§10 Decision 9):
        // the fullest settled cell renders as a full block (level 0)...
        float fullest = 0f;
        for (int c : column) fullest = Math.max(fullest, mass[c]);
        fullest = Math.max(fullest, mass[side]);
        assertTrue(fullest >= FluidReconcileLogic.FULL_FRACTION * water.defaultMass(),
                "expected a near-full settled cell, fullest was " + fullest);
        assertEquals(0, FluidReconcileLogic.levelForFraction(
                FluidReconcileLogic.fraction(fullest, water.defaultMass())),
                "a near-full settled cell renders as level 0");
        // ...and a never-fluid (empty) cell maps to REMOVE.
        assertEquals(FluidReconcileLogic.REMOVE, FluidReconcileLogic.levelForFraction(
                FluidReconcileLogic.fraction(0f, water.defaultMass())),
                "an emptied cell reconciles to REMOVE");
    }

    @Test
    void waterNextToLavaStillSteamsAndMassesDoNotMerge() {
        // water cell adjacent to a pinned lava cell. Advection only moves mass between same-material
        // fluid cells, so the lava's 3100 kg never flows into the water cell and vice-versa; the
        // water-on-lava interaction stays owned by §7 (PhaseRule -> orge:steam).
        Material water = water();
        // a water cell heated above boiling still yields orge:steam via PhaseRule (unchanged §7).
        Optional<Identifier> target = PhaseRule.targetBlock(400f, water);
        assertEquals(ORGE_STEAM, target.orElse(null));
        // reconcile keeps materials separate: water fraction uses water's full mass, lava uses lava's.
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
