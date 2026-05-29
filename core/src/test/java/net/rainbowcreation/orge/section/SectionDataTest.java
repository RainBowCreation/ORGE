package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link SectionData} — covers promote/demote, raw array views,
 * the {@code full} factory, and value-equality.
 */
class SectionDataTest {

    // -------------------------------------------------------------------------
    // Helper: build a FULL section with identical temperature/mass in every cell
    // -------------------------------------------------------------------------

    private static SectionData fullUniform(float temp, float mass) {
        float[] temps = new float[SectionData.CELLS];
        float[] masses = new float[SectionData.CELLS];
        Arrays.fill(temps, temp);
        Arrays.fill(masses, mass);
        return SectionData.full(temps, masses);
    }

    // -------------------------------------------------------------------------
    // 1. uniform() factory basics
    // -------------------------------------------------------------------------

    @Test
    void uniform_formIsUniform_andReadersReturnValues() {
        SectionData s = SectionData.uniform(290f, 1000f);

        assertEquals(SectionData.Form.UNIFORM, s.form());
        assertEquals(290f,  s.temperatureAt(0));
        assertEquals(1000f, s.massAt(4095));
    }

    // -------------------------------------------------------------------------
    // 2. Promotion on write — setTemperature
    // -------------------------------------------------------------------------

    @Test
    void setTemperature_promotesUniformToFull_andBackfillsOtherCells() {
        SectionData s = SectionData.uniform(290f, 1000f);

        s.setTemperature(10, 500f);

        assertEquals(SectionData.Form.FULL, s.form(), "should be FULL after write");
        assertEquals(500f, s.temperatureAt(10),        "written cell correct");
        assertEquals(290f, s.temperatureAt(0),          "other temp cell back-filled");
        assertEquals(1000f, s.massAt(0),                "mass back-filled too");
    }

    @Test
    void setMass_promotesUniformToFull_andBackfillsOtherCells() {
        SectionData s = SectionData.uniform(290f, 1000f);

        s.setMass(20, 500f);

        assertEquals(SectionData.Form.FULL, s.form(),  "should be FULL after write");
        assertEquals(500f, s.massAt(20),                "written mass cell correct");
        assertEquals(1000f, s.massAt(0),                "other mass cell back-filled");
        assertEquals(290f,  s.temperatureAt(0),         "temperature back-filled too");
    }

    // -------------------------------------------------------------------------
    // 3. temperatureArray() and massArray() — live views, force-promote UNIFORM
    // -------------------------------------------------------------------------

    @Test
    void temperatureArray_onUniform_promotesAndReturnsLiveArray() {
        SectionData s = SectionData.uniform(290f, 1000f);

        float[] arr = s.temperatureArray();

        assertNotNull(arr);
        assertEquals(SectionData.CELLS, arr.length, "array must be length 4096");
        assertEquals(SectionData.Form.FULL, s.form(), "should be FULL after temperatureArray()");

        // Mutation is visible via temperatureAt
        arr[5] = 123f;
        assertEquals(123f, s.temperatureAt(5), "mutation of returned array visible via temperatureAt");
    }

    @Test
    void massArray_onUniform_promotesAndReturnsLiveArray() {
        SectionData s = SectionData.uniform(290f, 1000f);

        float[] arr = s.massArray();

        assertNotNull(arr);
        assertEquals(SectionData.CELLS, arr.length, "array must be length 4096");
        assertEquals(SectionData.Form.FULL, s.form(), "should be FULL after massArray()");

        arr[7] = 42f;
        assertEquals(42f, s.massAt(7), "mutation of returned mass array visible via massAt");
    }

    // -------------------------------------------------------------------------
    // 4. demoteIfUniform()
    // -------------------------------------------------------------------------

    @Test
    void demoteIfUniform_whenAllCellsEqual_returnsTrueAndBecomesUniform() {
        SectionData s = fullUniform(300f, 1000f);
        assertEquals(SectionData.Form.FULL, s.form());

        boolean result = s.demoteIfUniform();

        assertTrue(result,                                       "demote should return true");
        assertEquals(SectionData.Form.UNIFORM, s.form(),         "should be UNIFORM");
        assertEquals(300f,  s.temperatureAt(0),                  "temperature preserved");
        assertEquals(1000f, s.massAt(0),                         "mass preserved");
    }

    @Test
    void demoteIfUniform_whenOneCellDiffers_returnsFalseAndStaysFull() {
        SectionData s = fullUniform(300f, 1000f);
        s.setTemperature(100, 301f); // one cell differs

        boolean result = s.demoteIfUniform();

        assertFalse(result,                                    "should not demote with differing cell");
        assertEquals(SectionData.Form.FULL, s.form(),          "should remain FULL");
    }

    @Test
    void demoteIfUniform_whenAlreadyUniform_returnsTrue() {
        SectionData s = SectionData.uniform(290f, 1000f);
        assertEquals(SectionData.Form.UNIFORM, s.form());

        boolean result = s.demoteIfUniform();

        assertTrue(result, "already-UNIFORM should return true");
        assertEquals(SectionData.Form.UNIFORM, s.form(), "should remain UNIFORM");
    }

    // -------------------------------------------------------------------------
    // 5. equalsValue()
    // -------------------------------------------------------------------------

    @Test
    void equalsValue_uniformEqualsFullWithSameValues() {
        SectionData u = SectionData.uniform(290f, 1000f);
        SectionData f = fullUniform(290f, 1000f);

        assertTrue(u.equalsValue(f),  "uniform(290,1000) should equal full with 290/1000 everywhere");
        assertTrue(f.equalsValue(u),  "symmetric");
    }

    @Test
    void equalsValue_returnsFalseWhenOneCellDiffers() {
        SectionData u = SectionData.uniform(290f, 1000f);
        SectionData f = fullUniform(290f, 1000f);
        f.setTemperature(0, 291f); // one cell differs

        assertFalse(u.equalsValue(f), "should be unequal when a cell differs");
    }

    // -------------------------------------------------------------------------
    // 6. full() factory
    // -------------------------------------------------------------------------

    @Test
    void full_wrongLengthArrays_throwIllegalArgumentException() {
        float[] shortArr = new float[10];
        float[] goodArr  = new float[SectionData.CELLS];

        assertThrows(IllegalArgumentException.class,
                () -> SectionData.full(shortArr, goodArr),
                "short temperature array should throw");
        assertThrows(IllegalArgumentException.class,
                () -> SectionData.full(goodArr, shortArr),
                "short mass array should throw");
    }

    @Test
    void full_correctLength_buildsFULLSectionReflectingArrayContents() {
        float[] temps  = new float[SectionData.CELLS];
        float[] masses = new float[SectionData.CELLS];
        temps[42]   = 350f;
        masses[100] = 800f;

        SectionData s = SectionData.full(temps, masses);

        assertEquals(SectionData.Form.FULL, s.form());
        assertEquals(350f, s.temperatureAt(42));
        assertEquals(800f, s.massAt(100));
        assertEquals(0f,   s.temperatureAt(0)); // default
    }

    @Test
    void full_adoptsArraysByReference() {
        float[] temps  = new float[SectionData.CELLS];
        float[] masses = new float[SectionData.CELLS];

        SectionData s = SectionData.full(temps, masses);

        // Mutate source array — change should be visible through the section
        temps[99] = 999f;
        assertEquals(999f, s.temperatureAt(99),
                "full() adopts array by reference — mutation of source visible via temperatureAt");
    }
}
