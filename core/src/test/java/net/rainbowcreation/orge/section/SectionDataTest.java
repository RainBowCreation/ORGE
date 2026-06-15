package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link SectionData} — covers promote/demote, raw array views, the {@code full}
 * factory, and value-equality over the law-§7 EXTENSIVE state: enthalpy {@code E [J]} (replacing
 * stored raw temperature) and momentum {@code (px,py,pz) [kg·m/s]} (replacing stored raw velocity).
 * Intensive T/v are derived at the boundary, never stored here — so these tests assert the channel
 * carries the extensive quantity (e.g. halving mass does NOT halve stored momentum).
 */
class SectionDataTest {

    // -------------------------------------------------------------------------
    // Helper: build a FULL section with identical enthalpy/mass in every cell
    // -------------------------------------------------------------------------

    private static SectionData fullUniform(float enthalpy, float mass) {
        float[] enths = new float[SectionData.CELLS];
        float[] masses = new float[SectionData.CELLS];
        Arrays.fill(enths, enthalpy);
        Arrays.fill(masses, mass);
        return SectionData.full(enths, masses);
    }

    // -------------------------------------------------------------------------
    // 1. uniform() factory basics
    // -------------------------------------------------------------------------

    @Test
    void uniform_formIsUniform_andReadersReturnValues() {
        SectionData s = SectionData.uniform(290_000f, 1000f);

        assertEquals(SectionData.Form.UNIFORM, s.form());
        assertEquals(290_000f, s.enthalpyAt(0));
        assertEquals(1000f, s.massAt(4095));
    }

    // -------------------------------------------------------------------------
    // 2. Promotion on write — setEnthalpy
    // -------------------------------------------------------------------------

    @Test
    void setEnthalpy_promotesUniformToFull_andBackfillsOtherCells() {
        SectionData s = SectionData.uniform(290_000f, 1000f);

        s.setEnthalpy(10, 500_000f);

        assertEquals(SectionData.Form.FULL, s.form(), "should be FULL after write");
        assertEquals(500_000f, s.enthalpyAt(10), "written cell correct");
        assertEquals(290_000f, s.enthalpyAt(0), "other E cell back-filled");
        assertEquals(1000f, s.massAt(0), "mass back-filled too");
    }

    @Test
    void setMass_promotesUniformToFull_andBackfillsOtherCells() {
        SectionData s = SectionData.uniform(290_000f, 1000f);

        s.setMass(20, 500f);

        assertEquals(SectionData.Form.FULL, s.form(), "should be FULL after write");
        assertEquals(500f, s.massAt(20), "written mass cell correct");
        assertEquals(1000f, s.massAt(0), "other mass cell back-filled");
        assertEquals(290_000f, s.enthalpyAt(0), "enthalpy back-filled too");
    }

    // -------------------------------------------------------------------------
    // 3. enthalpyArray() and massArray() — live views, force-promote UNIFORM
    // -------------------------------------------------------------------------

    @Test
    void enthalpyArray_onUniform_promotesAndReturnsLiveArray() {
        SectionData s = SectionData.uniform(290_000f, 1000f);

        float[] arr = s.enthalpyArray();

        assertNotNull(arr);
        assertEquals(SectionData.CELLS, arr.length, "array must be length 4096");
        assertEquals(SectionData.Form.FULL, s.form(), "should be FULL after enthalpyArray()");

        // Mutation is visible via enthalpyAt
        arr[5] = 123f;
        assertEquals(123f, s.enthalpyAt(5), "mutation of returned array visible via enthalpyAt");
    }

    @Test
    void massArray_onUniform_promotesAndReturnsLiveArray() {
        SectionData s = SectionData.uniform(290_000f, 1000f);

        float[] arr = s.massArray();

        assertNotNull(arr);
        assertEquals(SectionData.CELLS, arr.length, "array must be length 4096");
        assertEquals(SectionData.Form.FULL, s.form(), "should be FULL after massArray()");

        arr[7] = 42f;
        assertEquals(42f, s.massAt(7), "mutation of returned mass array visible via massAt");
    }

    // -------------------------------------------------------------------------
    // 4. Momentum channel (extensive p — NOT raw velocity; law §7)
    // -------------------------------------------------------------------------

    @Test
    void momentum_defaultsToZeroAndRoundTripsPerCell() {
        SectionData s = SectionData.uniform(300_000f, 1000f);

        assertEquals(0f, s.momXAt(0), "momentum defaults to 0 (resting)");

        s.setMomentum(42, 1500f, -2000f, 250f); // promotes to FULL

        assertEquals(SectionData.Form.FULL, s.form(), "setMomentum promotes to FULL");
        assertEquals(1500f, s.momXAt(42));
        assertEquals(-2000f, s.momYAt(42));
        assertEquals(250f, s.momZAt(42));
        assertEquals(0f, s.momXAt(43), "other cells still resting");
    }

    @Test
    void momentumIsExtensive_halvingMassDoesNotHalveStoredMomentum() {
        // Law §7: the channel stores EXTENSIVE momentum p, NOT raw velocity. Velocity v = p/mass is
        // derived at the boundary. If the field were raw velocity, halving mass would leave it
        // unchanged; if it were momentum, the stored value is independent of mass entirely. We assert
        // the field is momentum by showing the stored value does NOT track mass at all.
        SectionData s = SectionData.uniform(300_000f, 1000f);
        s.setMass(7, 1000f);
        s.setMomentum(7, 1000f, 0f, 0f); // p = 1000 kg·m/s  (=> v = 1 m/s at m=1000)

        assertEquals(1000f, s.momXAt(7), "stored value is the extensive momentum");

        // Halve the cell mass: a derived velocity v = p/m would DOUBLE, but the STORED momentum is
        // untouched (it is not a raw velocity, and not auto-scaled by mass).
        s.setMass(7, 500f);
        assertEquals(1000f, s.momXAt(7),
                "stored momentum is mass-independent (extensive) — halving mass does NOT halve it");
        // The DERIVED velocity at the boundary is p/m = 1000/500 = 2 m/s (computed by callers, not stored).
        assertEquals(2.0f, s.momXAt(7) / s.massAt(7), 1e-5f, "derived v = p/mass doubles as mass halves");
    }

    @Test
    void momentumArraysPromoteOnDemandAndAreLive() {
        SectionData s = SectionData.uniform(300_000f, 1000f);
        float[] px = s.momXArray();
        assertEquals(SectionData.CELLS, px.length);
        px[7] = 3000f;
        assertEquals(3000f, s.momXAt(7), "momXArray is the live backing array");
        assertTrue(s.hasMomentum(), "hasMomentum true once allocated");
    }

    // -------------------------------------------------------------------------
    // 5. demoteIfUniform()
    // -------------------------------------------------------------------------

    @Test
    void demoteIfUniform_whenAllCellsEqual_returnsTrueAndBecomesUniform() {
        SectionData s = fullUniform(300_000f, 1000f);
        assertEquals(SectionData.Form.FULL, s.form());

        boolean result = s.demoteIfUniform();

        assertTrue(result, "demote should return true");
        assertEquals(SectionData.Form.UNIFORM, s.form(), "should be UNIFORM");
        assertEquals(300_000f, s.enthalpyAt(0), "enthalpy preserved");
        assertEquals(1000f, s.massAt(0), "mass preserved");
    }

    @Test
    void demoteIfUniform_whenOneCellDiffers_returnsFalseAndStaysFull() {
        SectionData s = fullUniform(300_000f, 1000f);
        s.setEnthalpy(100, 301_000f); // one cell differs

        boolean result = s.demoteIfUniform();

        assertFalse(result, "should not demote with differing cell");
        assertEquals(SectionData.Form.FULL, s.form(), "should remain FULL");
    }

    @Test
    void demoteIfUniform_whenLastCellDiffers_returnsFalseAndStaysFull() {
        SectionData s = fullUniform(300_000f, 1000f);
        s.setEnthalpy(SectionData.CELLS - 1, 301_000f); // only index 4095 differs

        boolean result = s.demoteIfUniform();

        assertFalse(result, "should not demote when last cell differs");
        assertEquals(SectionData.Form.FULL, s.form(), "should remain FULL");
    }

    @Test
    void demoteIfUniform_staysFull_whenAnyMomentumNonzero() {
        // Velocity-ghost / bookkeeping-survival guard: an E+mass-uniform section that carries a moving
        // cell must NOT collapse — its momentum must survive the section form.
        SectionData s = fullUniform(300_000f, 1000f);
        s.setMomentum(100, 0.5f, 0f, 0f); // nonzero momentum, E+mass still uniform

        assertFalse(s.demoteIfUniform(), "nonzero momentum must keep section FULL");
        assertEquals(SectionData.Form.FULL, s.form());
        assertEquals(0.5f, s.momXAt(100), "momentum survives (not dropped)");
    }

    @Test
    void demoteIfUniform_staysFull_whenAnyPressureNonzero() {
        SectionData s = fullUniform(300_000f, 1000f);
        s.setPressure(100, 7f);

        assertFalse(s.demoteIfUniform(), "nonzero pressure must keep section FULL");
        assertEquals(7f, s.pAt(100), "pressure survives");
    }

    @Test
    void demoteIfUniform_staysFull_whenAnySwapReadyNonzero() {
        SectionData s = fullUniform(300_000f, 1000f);
        s.setSwapReady(100, 0.25f);

        assertFalse(s.demoteIfUniform(), "nonzero swapReady must keep section FULL (law #7 bookkeeping)");
        assertEquals(0.25f, s.swapReadyAt(100), "swapReady survives");
    }

    @Test
    void demoteIfUniform_collapses_whenUniformAndAllBookkeepingZero() {
        SectionData s = fullUniform(300_000f, 1000f);
        // Touch (and zero) every bookkeeping channel so the demote scans them all and still collapses.
        s.setMomentum(5, 0f, 0f, 0f);
        s.setPressure(5, 0f);
        s.setSwapReady(5, 0f);

        assertTrue(s.demoteIfUniform(), "uniform E+mass with all-zero bookkeeping collapses");
        assertEquals(SectionData.Form.UNIFORM, s.form());
        assertEquals(300_000f, s.enthalpyAt(0));
        assertEquals(1000f, s.massAt(0));
    }

    @Test
    void demoteIfUniform_whenAlreadyUniform_returnsTrue() {
        SectionData s = SectionData.uniform(290_000f, 1000f);
        assertEquals(SectionData.Form.UNIFORM, s.form());

        boolean result = s.demoteIfUniform();

        assertTrue(result, "already-UNIFORM should return true");
        assertEquals(SectionData.Form.UNIFORM, s.form(), "should remain UNIFORM");
    }

    // -------------------------------------------------------------------------
    // 6. equalsValue()
    // -------------------------------------------------------------------------

    @Test
    void equalsValue_uniformEqualsFullWithSameValues() {
        SectionData u = SectionData.uniform(290_000f, 1000f);
        SectionData f = fullUniform(290_000f, 1000f);

        assertTrue(u.equalsValue(f), "uniform(290k,1000) should equal full with 290k/1000 everywhere");
        assertTrue(f.equalsValue(u), "symmetric");
    }

    @Test
    void equalsValue_returnsFalseWhenOneCellDiffers() {
        SectionData u = SectionData.uniform(290_000f, 1000f);
        SectionData f = fullUniform(290_000f, 1000f);
        f.setEnthalpy(0, 291_000f); // one cell differs

        assertFalse(u.equalsValue(f), "should be unequal when a cell differs");
    }

    // -------------------------------------------------------------------------
    // 7. full() factory
    // -------------------------------------------------------------------------

    @Test
    void full_wrongLengthArrays_throwIllegalArgumentException() {
        float[] shortArr = new float[10];
        float[] goodArr = new float[SectionData.CELLS];

        assertThrows(IllegalArgumentException.class,
                () -> SectionData.full(shortArr, goodArr),
                "short enthalpy array should throw");
        assertThrows(IllegalArgumentException.class,
                () -> SectionData.full(goodArr, shortArr),
                "short mass array should throw");
    }

    @Test
    void full_correctLength_buildsFULLSectionReflectingArrayContents() {
        float[] enths = new float[SectionData.CELLS];
        float[] masses = new float[SectionData.CELLS];
        enths[42] = 350_000f;
        masses[100] = 800f;

        SectionData s = SectionData.full(enths, masses);

        assertEquals(SectionData.Form.FULL, s.form());
        assertEquals(350_000f, s.enthalpyAt(42));
        assertEquals(800f, s.massAt(100));
        assertEquals(0f, s.enthalpyAt(0)); // default
    }

    @Test
    void full_adoptsArraysByReference() {
        float[] enths = new float[SectionData.CELLS];
        float[] masses = new float[SectionData.CELLS];

        SectionData s = SectionData.full(enths, masses);

        // Mutate source array — change should be visible through the section
        enths[99] = 999_000f;
        assertEquals(999_000f, s.enthalpyAt(99),
                "full() adopts array by reference — mutation of source visible via enthalpyAt");
    }
}
