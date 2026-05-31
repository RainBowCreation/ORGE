package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link CrossSectionFluidLogic#settleVerticalSeam}.
 *
 * <p>LUT indices used throughout:
 * <ul>
 *   <li>0 = VOID sentinel (solid, non-fluid, non-air)</li>
 *   <li>1 = WATER (FLUID state, maxMass 1000)</li>
 *   <li>2 = LAVA  (FLUID state, maxMass 3100)</li>
 *   <li>3 = AIR   (AIR state,   defaultMass ~1.2 kg)</li>
 *   <li>4 = STEAM (GAS state,   defaultMass 0.6 kg)</li>
 *   <li>5 = STONE (SOLID state, defaultMass 2500 kg)</li>
 * </ul>
 */
class CrossSectionFluidLogicTest {

    /* ------------------------------------------------------------------ */
    /*  LUT construction helpers                                            */
    /* ------------------------------------------------------------------ */

    private static final Identifier NS_ORGE = Identifier.fromNamespaceAndPath("orge", "test");

    private static Material mat(String name, Material.State state, float defaultMass, float maxMass) {
        Identifier id = Identifier.fromNamespaceAndPath("orge", name);
        return new Material(
                id,
                0.6f,           // thermalConductivity
                4186f,          // heatCapacity
                0.001f,         // viscosity
                defaultMass,    // defaultMass
                0f,             // molarMass
                Float.POSITIVE_INFINITY,  // maxTemp
                Float.NEGATIVE_INFINITY,  // minTemp
                null, null, null,
                Float.NaN, false,
                state,
                0f,             // minFlowMass
                maxMass);       // maxMass (0 = fall back to defaultMass)
    }

    /** Builds the shared LUT: index 0=VOID, 1=WATER, 2=LAVA, 3=AIR, 4=STEAM, 5=STONE. */
    private static List<Material> buildLut() {
        List<Material> lut = new ArrayList<>();
        lut.add(MaterialLut.VOID);                                                  // 0
        lut.add(mat("water", Material.State.FLUID, 1000f, 1000f));                  // 1
        lut.add(mat("lava",  Material.State.FLUID, 3100f, 3100f));                  // 2
        lut.add(mat("air",   Material.State.AIR,   1.2f,  1.2f));                   // 3
        lut.add(mat("steam", Material.State.GAS,   0.6f,  0.6f));                   // 4
        lut.add(mat("stone", Material.State.SOLID, 2500f, 2500f));                  // 5
        return lut;
    }

    // species char constants for readability
    private static final char VOID  = 0;
    private static final char WATER = 1;
    private static final char LAVA  = 2;
    private static final char AIR   = 3;
    private static final char STEAM = 4;
    private static final char STONE = 5;

    /** Builds empty 256-cell planes with everything at VOID/0. */
    private static float[] masses(int size)  { return new float[size]; }
    private static float[] temps(int size)   { return new float[size]; }
    private static char[]  species(int size) { return new char[size]; }

    /* ------------------------------------------------------------------ */
    /*  (a) SWAP: water over real air                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void swapWaterOverAir() {
        List<Material> lut = buildLut();
        int c = 7; // arbitrary column

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 1000f;  tempA[c] = 290f;  spA[c] = WATER;
        massB[c] = 1.2f;   tempB[c] = 300f;  spB[c] = AIR;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        // B now holds the water
        assertEquals(1000f, massB[c], 1e-4f, "B should have water's mass");
        assertEquals(290f,  tempB[c], 1e-3f, "B should have water's temp");
        assertEquals(WATER, spB[c], "B should be water species");

        // A now holds the air
        assertEquals(1.2f,  massA[c], 1e-4f, "A should have air's mass");
        assertEquals(300f,  tempA[c], 1e-3f, "A should have air's temp");
        assertEquals(AIR,   spA[c], "A should be air species");

        // Both columns changed
        assertTrue(r.changedA()[c], "changedA must be true after swap");
        assertTrue(r.changedB()[c], "changedB must be true after swap");

        // No 1.2 kg residue: A is legitimately air, not a ghost trace
        assertNotEquals(0, spA[c], "Swap leaves real air in A, not void");

        // Conservation
        assertEquals(r.massBefore(), r.massAfter(), 1e-3, "mass must be conserved");
    }

    /* ------------------------------------------------------------------ */
    /*  (b) DEPOSIT: water over same water with room                        */
    /* ------------------------------------------------------------------ */

    @Test
    void depositWaterOverPartialWater() {
        List<Material> lut = buildLut();
        int c = 0;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 1000f;  tempA[c] = 350f;  spA[c] = WATER;
        massB[c] = 300f;   tempB[c] = 280f;  spB[c] = WATER;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        float dm = 700f; // cap = 1000 - 300 = 700, donor has 1000 → dm = 700

        // B should be full at 1000 with enthalpy-mixed temp
        assertEquals(1000f, massB[c], 1e-3f, "B should be at capacity");
        float expectedTemp = (300f * 280f + dm * 350f) / 1000f;
        assertEquals(expectedTemp, tempB[c], 1e-3f, "B temp should be enthalpy mix");
        assertEquals(WATER, spB[c], "B species unchanged");

        // A should have 300 left, temp unchanged
        assertEquals(300f, massA[c], 1e-3f, "A should have 300 kg left");
        assertEquals(350f, tempA[c], 1e-3f, "A temp unchanged");
        assertEquals(WATER, spA[c], "A species unchanged");

        assertTrue(r.changedA()[c], "changedA set after deposit");
        assertTrue(r.changedB()[c], "changedB set after deposit");

        assertEquals(r.massBefore(), r.massAfter(), 1e-3, "mass conserved");
    }

    /* ------------------------------------------------------------------ */
    /*  (c) No transfer: full same-water receiver                           */
    /* ------------------------------------------------------------------ */

    @Test
    void noTransferReceiverFull() {
        List<Material> lut = buildLut();
        int c = 10;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 1000f;  tempA[c] = 300f;  spA[c] = WATER;
        massB[c] = 1000f;  tempB[c] = 280f;  spB[c] = WATER;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(1000f, massA[c], 1e-6f, "A unchanged");
        assertEquals(1000f, massB[c], 1e-6f, "B unchanged");
        assertFalse(r.changedA()[c], "no change in A");
        assertFalse(r.changedB()[c], "no change in B");

        assertEquals(r.massBefore(), r.massAfter(), 1e-3, "mass conserved");
    }

    /* ------------------------------------------------------------------ */
    /*  (d) No transfer: water over different fluid, solid, void            */
    /* ------------------------------------------------------------------ */

    @Test
    void noTransferWaterOverLava() {
        List<Material> lut = buildLut();
        int c = 5;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 500f;   tempA[c] = 300f;  spA[c] = WATER;
        massB[c] = 3100f;  tempB[c] = 1200f; spB[c] = LAVA;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(500f,  massA[c], 1e-6f);
        assertEquals(3100f, massB[c], 1e-6f);
        assertFalse(r.changedA()[c]);
        assertFalse(r.changedB()[c]);
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    @Test
    void noTransferWaterOverSolid() {
        List<Material> lut = buildLut();
        int c = 20;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 500f;   tempA[c] = 300f;  spA[c] = WATER;
        massB[c] = 2500f;  tempB[c] = 300f;  spB[c] = STONE;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(500f,  massA[c], 1e-6f);
        assertEquals(2500f, massB[c], 1e-6f);
        assertFalse(r.changedA()[c]);
        assertFalse(r.changedB()[c]);
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    @Test
    void noTransferWaterOverVoid() {
        List<Material> lut = buildLut();
        int c = 100;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 500f;  tempA[c] = 300f;  spA[c] = WATER;
        massB[c] = 0f;    tempB[c] = 0f;    spB[c] = VOID;  // void/empty

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(500f, massA[c], 1e-6f);
        assertEquals(0f,   massB[c], 1e-6f);
        assertFalse(r.changedA()[c]);
        assertFalse(r.changedB()[c]);
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    /* ------------------------------------------------------------------ */
    /*  (e) GAS donor (steam) over air: no transfer                         */
    /* ------------------------------------------------------------------ */

    @Test
    void noTransferGasDonor() {
        List<Material> lut = buildLut();
        int c = 15;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 0.6f;  tempA[c] = 400f;  spA[c] = STEAM;  // gas, should not fall
        massB[c] = 1.2f;  tempB[c] = 300f;  spB[c] = AIR;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(0.6f, massA[c], 1e-6f, "steam donor unchanged");
        assertEquals(1.2f, massB[c], 1e-6f, "air receiver unchanged");
        assertFalse(r.changedA()[c]);
        assertFalse(r.changedB()[c]);
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    /* ------------------------------------------------------------------ */
    /*  (f) Partial deposit and small-mass swap                             */
    /* ------------------------------------------------------------------ */

    @Test
    void partialDepositLeavesRemainder() {
        List<Material> lut = buildLut();
        int c = 3;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        // donor 50, receiver 995 with cap 1000 -> dm = 5, A retains 45
        massA[c] = 50f;   tempA[c] = 300f;  spA[c] = WATER;
        massB[c] = 995f;  tempB[c] = 290f;  spB[c] = WATER;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(45f,   massA[c], 1e-3f, "A retains 45");
        assertEquals(1000f, massB[c], 1e-3f, "B full at 1000");
        assertTrue(r.changedA()[c]);
        assertTrue(r.changedB()[c]);
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    @Test
    void smallDonorOverAirIsSwap() {
        List<Material> lut = buildLut();
        int c = 50;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 50f;  tempA[c] = 300f;  spA[c] = WATER;
        massB[c] = 1.2f; tempB[c] = 295f;  spB[c] = AIR;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        // After swap: B=water 50@300, A=air 1.2@295
        assertEquals(50f,   massB[c], 1e-4f);
        assertEquals(300f,  tempB[c], 1e-3f);
        assertEquals(WATER, spB[c]);
        assertEquals(1.2f,  massA[c], 1e-4f);
        assertEquals(295f,  tempA[c], 1e-3f);
        assertEquals(AIR,   spA[c]);
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    /* ------------------------------------------------------------------ */
    /*  (g) Donor drains to zero: species retained                          */
    /* ------------------------------------------------------------------ */

    @Test
    void depositDrainsToZeroSpeciesKept() {
        List<Material> lut = buildLut();
        int c = 200;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        // donor 999, receiver 1, cap=999 -> dm=999, A goes to 0 -> massA[c]=0, spA[c] still WATER
        massA[c] = 999f;  tempA[c] = 310f;  spA[c] = WATER;
        massB[c] = 1f;    tempB[c] = 280f;  spB[c] = WATER;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(0f,    massA[c], 1e-4f, "A drained to zero");
        assertEquals(WATER, spA[c],          "A species kept (water) after drain");
        assertEquals(1000f, massB[c], 1e-3f, "B at capacity");
        assertTrue(r.changedA()[c]);
        assertTrue(r.changedB()[c]);
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    @Test
    void depositCapTooSmallToMeetEpsIsSkipped() {
        List<Material> lut = buildLut();
        int c = 100;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        // receiver is (1000 - 5e-5) full; cap = 5e-5 < ADV_EPS = 1e-4 -> skip
        float receiverMass = 1000f - 5e-5f;
        massA[c] = 500f;          tempA[c] = 300f;  spA[c] = WATER;
        massB[c] = receiverMass;  tempB[c] = 280f;  spB[c] = WATER;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(500f,         massA[c], 1e-6f, "A unchanged (cap too small)");
        assertEquals(receiverMass, massB[c], 1e-6f, "B unchanged (cap too small)");
        assertFalse(r.changedA()[c], "no change flagged A");
        assertFalse(r.changedB()[c], "no change flagged B");
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    /* ------------------------------------------------------------------ */
    /*  (h) Full 256-cell mixed plane: sample columns + global conservation */
    /* ------------------------------------------------------------------ */

    @Test
    void mixedPlane256CellsConservesMass() {
        List<Material> lut = buildLut();

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        // Set up various scenarios across the plane
        for (int c = 0; c < 256; c++) {
            switch (c % 6) {
                case 0 -> { // water over air -> swap
                    massA[c] = 800f;  tempA[c] = 290f;  spA[c] = WATER;
                    massB[c] = 1.2f;  tempB[c] = 300f;  spB[c] = AIR;
                }
                case 1 -> { // water over partial water -> deposit
                    massA[c] = 600f;  tempA[c] = 310f;  spA[c] = WATER;
                    massB[c] = 200f;  tempB[c] = 270f;  spB[c] = WATER;
                }
                case 2 -> { // water over full water -> no transfer
                    massA[c] = 500f;  tempA[c] = 300f;  spA[c] = WATER;
                    massB[c] = 1000f; tempB[c] = 285f;  spB[c] = WATER;
                }
                case 3 -> { // water over lava -> no transfer
                    massA[c] = 400f;  tempA[c] = 290f;  spA[c] = WATER;
                    massB[c] = 3100f; tempB[c] = 1200f; spB[c] = LAVA;
                }
                case 4 -> { // steam over air -> no transfer (gas)
                    massA[c] = 0.6f;  tempA[c] = 400f;  spA[c] = STEAM;
                    massB[c] = 1.2f;  tempB[c] = 300f;  spB[c] = AIR;
                }
                case 5 -> { // water over void -> no transfer
                    massA[c] = 300f;  tempA[c] = 300f;  spA[c] = WATER;
                    massB[c] = 0f;    tempB[c] = 0f;    spB[c] = VOID;
                }
            }
        }

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        // Global mass conservation
        assertEquals(r.massBefore(), r.massAfter(), 1e-2,
                "Global mass conservation across 256 mixed columns");

        // Spot check column 0: water over air -> SWAP
        assertEquals(800f,  massB[0], 1e-3f, "col0: B has water mass");
        assertEquals(290f,  tempB[0], 1e-3f, "col0: B has water temp");
        assertEquals(WATER, spB[0],           "col0: B is water");
        assertEquals(1.2f,  massA[0], 1e-3f, "col0: A has air mass");
        assertEquals(AIR,   spA[0],           "col0: A is air");
        assertTrue(r.changedA()[0] && r.changedB()[0], "col0: both changed");

        // Spot check column 1: water deposit
        assertEquals(800f,  massB[1], 1e-3f, "col1: B has 200+600=800 (partial deposit)");
        assertEquals(WATER, spB[1],           "col1: B still water");
        assertTrue(r.changedA()[1] && r.changedB()[1], "col1: both changed");

        // Spot check column 2: no transfer (full)
        assertEquals(500f,  massA[2], 1e-6f, "col2: A unchanged");
        assertEquals(1000f, massB[2], 1e-6f, "col2: B unchanged");
        assertFalse(r.changedA()[2], "col2: no change A");
        assertFalse(r.changedB()[2], "col2: no change B");

        // Spot check column 4: steam no transfer
        assertEquals(0.6f, massA[4], 1e-6f, "col4: steam unchanged");
        assertEquals(1.2f, massB[4], 1e-6f, "col4: air unchanged");
        assertFalse(r.changedA()[4], "col4: no change A");
        assertFalse(r.changedB()[4], "col4: no change B");
    }

    /* ------------------------------------------------------------------ */
    /*  Edge cases                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void donorWithZeroMassIsSkipped() {
        List<Material> lut = buildLut();
        int c = 33;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        // Species says water, but mass is 0 (drained donor from a previous tick) -> skip
        massA[c] = 0f;   tempA[c] = 300f;  spA[c] = WATER;
        massB[c] = 1.2f; tempB[c] = 295f;  spB[c] = AIR;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        assertEquals(0f,   massA[c], 1e-6f, "A still 0 mass");
        assertEquals(1.2f, massB[c], 1e-6f, "B unchanged");
        assertFalse(r.changedA()[c]);
        assertFalse(r.changedB()[c]);
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }

    /* ------------------------------------------------------------------ */
    /*  (i) Non-finite guard: NaN donor or NaN receiver is skipped          */
    /* ------------------------------------------------------------------ */

    /**
     * I1 regression: corrupt (NaN) input must never propagate NaN into any output array.
     * Three columns are set up:
     *   col 10 — NaN tempA over real air → donor guard must skip it
     *   col 20 — good water donor over NaN massB → receiver guard must skip it
     *   col 30 — NaN massA (water species) over real air → donor non-finite guard skips it
     * All other columns are left as VOID/0.  No output array may contain NaN after the call.
     */
    @Test
    void nonFiniteInputIsSkippedWithoutNaNPropagation() {
        List<Material> lut = buildLut();

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        // col 10: NaN temp on donor → donor guard should skip
        massA[10] = 500f;          tempA[10] = Float.NaN;  spA[10] = WATER;
        massB[10] = 1.2f;          tempB[10] = 300f;       spB[10] = AIR;

        // col 20: valid donor over receiver with NaN mass → receiver guard should skip
        massA[20] = 500f;          tempA[20] = 300f;       spA[20] = WATER;
        massB[20] = Float.NaN;     tempB[20] = 300f;       spB[20] = WATER;

        // col 30: NaN mass on donor (despite species=WATER, NaN > ADV_EPS is false, but
        //         the explicit isFinite guard still fires and skips it cleanly)
        massA[30] = Float.NaN;     tempA[30] = 300f;       spA[30] = WATER;
        massB[30] = 1.2f;          tempB[30] = 300f;       spB[30] = AIR;

        // Should not throw; should not write NaN anywhere
        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        // Verify the guard prevented NaN from spreading to OTHER cells (not the corrupt ones).
        // We check all 256 outputs on each side, skipping only the cells we deliberately
        // corrupted as inputs (since those are not mutated, they remain NaN by design).
        for (int c = 0; c < 256; c++) {
            if (c != 10 && c != 30) {  // col 10 tempA, col 30 massA were set NaN as inputs
                assertFalse(Float.isNaN(massA[c]),
                        "massA[" + c + "] must not be NaN after call");
            }
            if (c != 10) {  // col 10 tempA was set NaN as input
                assertFalse(Float.isNaN(tempA[c]),
                        "tempA[" + c + "] must not be NaN after call");
            }
            if (c != 20) {  // col 20 massB was set NaN as input
                assertFalse(Float.isNaN(massB[c]),
                        "massB[" + c + "] must not be NaN after call");
                assertFalse(Float.isNaN(tempB[c]),
                        "tempB[" + c + "] must not be NaN after call");
            }
        }

        // The corrupt columns must not have been mutated by the logic
        assertFalse(r.changedA()[10], "col10 (NaN tempA donor) must not be flagged changed");
        assertFalse(r.changedB()[10], "col10 receiver must not be flagged changed");
        assertEquals(1.2f, massB[10], 1e-6f, "col10 air receiver must be unchanged");

        assertFalse(r.changedA()[20], "col20 (NaN massB receiver) must not be flagged changed");
        assertFalse(r.changedB()[20], "col20 receiver must not be flagged changed");
        assertEquals(500f, massA[20], 1e-6f, "col20 donor must be unchanged");

        assertFalse(r.changedA()[30], "col30 (NaN massA donor) must not be flagged changed");
        assertFalse(r.changedB()[30], "col30 receiver must not be flagged changed");
        assertEquals(1.2f, massB[30], 1e-6f, "col30 air receiver must be unchanged");
    }

    @Test
    void lavaOverSameLavaDeposit() {
        List<Material> lut = buildLut();
        int c = 77;

        float[] massA = masses(256);  float[] tempA = temps(256);  char[] spA = species(256);
        float[] massB = masses(256);  float[] tempB = temps(256);  char[] spB = species(256);

        massA[c] = 1000f;  tempA[c] = 1200f;  spA[c] = LAVA;
        massB[c] = 2000f;  tempB[c] = 1100f;  spB[c] = LAVA;

        CrossSectionFluidLogic.SeamResult r =
                CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

        // cap = 3100 - 2000 = 1100, donor=1000 -> dm=1000
        float dm = 1000f;
        float expectedTemp = (2000f * 1100f + dm * 1200f) / 3000f;

        assertEquals(3000f,        massB[c], 1e-3f);
        assertEquals(expectedTemp, tempB[c], 1e-1f);
        assertEquals(LAVA,         spB[c]);
        assertEquals(0f,           massA[c], 1e-3f);  // drained to 0
        assertEquals(LAVA,         spA[c], "species kept after drain");
        assertEquals(r.massBefore(), r.massAfter(), 1e-3);
    }
}
