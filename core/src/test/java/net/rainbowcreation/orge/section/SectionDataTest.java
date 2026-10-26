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
        double[] enths = new double[SectionData.CELLS];
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

        double[] arr = s.enthalpyArray();

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
                () -> SectionData.full(new double[10], goodArr),
                "short enthalpy array should throw");
        assertThrows(IllegalArgumentException.class,
                () -> SectionData.full(new double[SectionData.CELLS], shortArr),
                "short mass array should throw");
    }

    @Test
    void full_correctLength_buildsFULLSectionReflectingArrayContents() {
        double[] enths = new double[SectionData.CELLS];
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
        double[] enths = new double[SectionData.CELLS];
        float[] masses = new float[SectionData.CELLS];

        SectionData s = SectionData.full(enths, masses);

        // Mutate source array — change should be visible through the section
        enths[99] = 999_000f;
        assertEquals(999_000f, s.enthalpyAt(99),
                "full() adopts array by reference — mutation of source visible via enthalpyAt");
    }

    // -------------------------------------------------------------------------
    // Composition slots 1..K-1 (DESIGN-LAW v5 #7; issue #27)
    // -------------------------------------------------------------------------

    private static final net.minecraft.resources.Identifier WATER =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "water");
    private static final net.minecraft.resources.Identifier SAND =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "sand");

    @Test
    void slotsMatchTheEngineCompositionWidth() {
        assertEquals(net.rainbowcreation.orge.engine.RegionMarshaller.MMC_K, SectionData.SLOTS,
                "section slots must equal the engine's K (law v5 #7)");
        assertEquals(net.rainbowcreation.orge.engine.RegionMarshaller.SLOT_N / 24, SectionData.SLOT_CELLS,
                "a section's slot layer is 1/24 of a column's slot channel");
    }

    @Test
    void freshSectionIsOneComponentEverywhere() {
        SectionData s = SectionData.uniform(300f, 1000f);
        assertFalse(s.hasSlots());
        for (int k = 1; k < SectionData.SLOTS; k++) {
            assertEquals(0f, s.slotMassAt(k, 42));
            assertEquals(MaterialPalette.VACUUM_ID, s.slotMaterialAt(k, 42));
        }
        assertEquals(1000f, s.cellMassAt(42), "M = m_0 for a one-component cell");
        net.minecraft.resources.Identifier[] ids = new net.minecraft.resources.Identifier[SectionData.SLOT_CELLS];
        float[] m = new float[SectionData.SLOT_CELLS];
        s.copySlotsInto(ids, m);
        assertNull(ids[0], "no layer ⇒ copy leaves the caller's empty default");
        assertEquals(SectionData.Form.UNIFORM, s.form(), "reading slots never promotes");
    }

    @Test
    void setSlotStoresIdAndMassAndSumsCellMass() {
        SectionData s = SectionData.uniform(300f, 1600f);
        s.setMaterialAt(7, SAND);
        s.setSlot(1, 7, WATER, 320f);
        s.setSlot(3, 7, SAND, 2.5f);
        assertTrue(s.hasSlots());
        assertEquals(SectionData.Form.FULL, s.form(), "a slot write promotes to FULL");
        assertEquals(WATER, s.slotMaterialAt(1, 7));
        assertEquals(320f, s.slotMassAt(1, 7));
        assertEquals(MaterialPalette.VACUUM_ID, s.slotMaterialAt(2, 7), "untouched slot is empty");
        assertEquals(0f, s.slotMassAt(2, 7));
        assertEquals(1600f + 320f + 2.5f, s.cellMassAt(7), "M = Σ_k m_k");
        assertEquals(1600f, s.massAt(7), "slot 0 untouched");

        net.minecraft.resources.Identifier[] ids = new net.minecraft.resources.Identifier[SectionData.SLOT_CELLS];
        float[] m = new float[SectionData.SLOT_CELLS];
        s.copySlotsInto(ids, m);
        assertEquals(WATER, ids[7]);
        assertEquals(320f, m[7]);
        assertEquals(MaterialPalette.VACUUM_ID, ids[SectionData.CELLS + 7]);
        assertEquals(SAND, ids[2 * SectionData.CELLS + 7]);
        assertEquals(2.5f, m[2 * SectionData.CELLS + 7]);
    }

    @Test
    void slotIndexOutsideOneToKMinusOneIsRejected() {
        SectionData s = SectionData.uniform(300f, 1000f);
        assertThrows(IllegalArgumentException.class, () -> s.setSlot(0, 0, WATER, 1f), "slot 0 is massAt/materialAt");
        assertThrows(IllegalArgumentException.class, () -> s.slotMassAt(SectionData.SLOTS, 0));
    }

    @Test
    void occupiedSlotBlocksDemotionAndEmptiedLayerIsReleased() {
        SectionData s = SectionData.uniform(300f, 1000f);
        s.setSlot(2, 9, WATER, 5f);
        assertFalse(s.demoteIfUniform(), "a cell with an occupied slot keeps the section FULL");
        assertTrue(s.hasSlots());
        assertEquals(5f, s.slotMassAt(2, 9), "demotion attempt never drops slot mass");

        s.setSlot(2, 9, MaterialPalette.VACUUM_ID, 0f);    // slot empties
        assertTrue(s.demoteIfUniform(), "an all-empty layer is the absent layer ⇒ section collapses");
        assertFalse(s.hasSlots());
        assertEquals(SectionData.Form.UNIFORM, s.form());
        assertEquals(1000f, s.cellMassAt(9));
    }

    /** Issue #44: the runtime E store is double — a heat quantum far below a float32 ULP of a massive
     *  cell's E is kept (a float store rounded it away on every tick). */
    @Test
    void runtimeEnthalpyHoldsSubFloatUlpHeat() {
        SectionData s = SectionData.uniform(6.0e8, 2700f);   // float32 ULP at 6e8 J is 64 J
        s.setEnthalpy(7, s.enthalpyAt(7) + 0.5);
        assertEquals(6.0e8 + 0.5, s.enthalpyAt(7), 0.0, "setEnthalpy keeps a 0.5 J quantum");
        double[] live = s.enthalpyArray();
        live[8] += 3.0;
        assertEquals(6.0e8 + 3.0, s.enthalpyAt(8), 0.0, "the live array is double too");
        assertEquals(6.0e8, s.enthalpyAt(9), 0.0, "promotion back-fills the exact uniform E");
    }
    // -------------------------------------------------------------------------
    // Off-thread hand-over (issue #51): copy-on-write share() + adopt()
    // -------------------------------------------------------------------------

    private static final net.minecraft.resources.Identifier STONE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "stone");

    /** A FULL section carrying every channel: E, mass, materials, momentum, p, swapReady, a slot. */
    private static SectionData everyChannel() {
        SectionData s = fullUniform(5e5f, 1000f);
        for (int i = 0; i < SectionData.CELLS; i++) {
            s.setMaterialAt(i, STONE);
            s.setMomentum(i, 1f, 2f, 3f);
            s.setPressure(i, 4f);
            s.setSwapReady(i, 0.5f);
        }
        s.setSlot(1, 7, WATER, 12f);
        return s;
    }

    @Test
    void share_viewKeepsTheCapturedStateThroughEveryLiveMutation() {
        SectionData live = everyChannel();
        SectionData view = live.share();

        live.setEnthalpy(0, 1.0);
        live.massArray()[1] = 2f;
        live.enthalpyArray()[2] = 3.0;
        live.setMass(3, 4f);
        live.setMaterialAt(4, WATER);
        live.setMomentum(5, 9f, 9f, 9f);
        live.momXArray()[6] = 9f;
        live.setPressure(7, 9f);
        live.pArray()[8] = 9f;
        live.setSwapReady(9, 9f);
        live.swapReadyArray()[10] = 9f;
        live.setSlot(1, 7, STONE, 99f);

        assertEquals(5e5f, view.enthalpyAt(0));
        assertEquals(1000f, view.massAt(1));
        assertEquals(5e5f, view.enthalpyAt(2));
        assertEquals(1000f, view.massAt(3));
        assertEquals(STONE, view.materialAt(4));
        assertEquals(1f, view.momXAt(5));
        assertEquals(1f, view.momXAt(6));
        assertEquals(4f, view.pAt(7));
        assertEquals(4f, view.pAt(8));
        assertEquals(0.5f, view.swapReadyAt(9));
        assertEquals(0.5f, view.swapReadyAt(10));
        assertEquals(WATER, view.slotMaterialAt(1, 7));
        assertEquals(12f, view.slotMassAt(1, 7));

        // ...while the live section has every change
        assertEquals(1.0, live.enthalpyAt(0));
        assertEquals(2f, live.massAt(1));
        assertEquals(3.0, live.enthalpyAt(2));
        assertEquals(4f, live.massAt(3));
        assertEquals(WATER, live.materialAt(4));
        assertEquals(9f, live.momXAt(5));
        assertEquals(9f, live.momXAt(6));
        assertEquals(9f, live.pAt(7));
        assertEquals(9f, live.pAt(8));
        assertEquals(9f, live.swapReadyAt(9));
        assertEquals(9f, live.swapReadyAt(10));
        assertEquals(STONE, live.slotMaterialAt(1, 7));
        assertEquals(99f, live.slotMassAt(1, 7));
    }

    /** The server-thread capture of a fully simulated section allocates the view object, not its 4096-cell
     *  channels (~170 KB per section, ~4 MB per column) — the snapshot's server-thread share of issue #51. */
    @Test
    void share_allocatesNoChannelCopy() {
        SectionData live = everyChannel();
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        live.share(); // warm up (class init, JIT)
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int k = 0; k < 24; k++) {
            live.share();
        }
        long perColumn = mx.getCurrentThreadAllocatedBytes() - before;
        assertTrue(perColumn < 16 * 1024, "24 shares (one column) allocated " + perColumn + " bytes");
    }

    @Test
    void adopt_installsEveryChannelOfTheNextSectionAndKeepsTheEditEpochs() {
        SectionData live = fullUniform(1f, 1f);
        live.markExternalEdit();                     // an edit after the (absent) snapshot: epochs differ
        SectionData next = everyChannel();
        next.setEnthalpy(11, 77.0);

        live.adopt(next);

        assertEquals(77.0, live.enthalpyAt(11));
        assertEquals(1000f, live.massAt(0));
        assertEquals(STONE, live.materialAt(0));
        assertEquals(1f, live.momXAt(0));
        assertEquals(4f, live.pAt(0));
        assertEquals(0.5f, live.swapReadyAt(0));
        assertEquals(WATER, live.slotMaterialAt(1, 7));
        assertTrue(live.editedSinceSnapshot(), "the live section's edit epochs are its own");
    }

    @Test
    void adopt_endsTheSharingOfTheOldArrays() {
        SectionData live = everyChannel();
        SectionData view = live.share();
        live.adopt(everyChannel());
        double[] adopted = live.enthalpyArray();
        live.setEnthalpy(0, 123.0);
        assertSame(adopted, live.enthalpyArray(), "the adopted arrays are not shared: mutated in place");
        assertEquals(5e5f, view.enthalpyAt(0), "and the old view is untouched");
    }
}
