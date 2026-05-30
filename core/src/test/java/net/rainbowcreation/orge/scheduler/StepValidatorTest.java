package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepValidatorTest {

    @Test
    void finiteInRangeValuesPassThroughUnchanged() {
        float[] result = {300f, 0f, 6000f, 285.5f};
        float[] fallback = {1f, 1f, 1f, 1f};
        float[] out = StepValidator.clean(result, fallback);
        assertArrayEquals(new float[]{300f, 0f, 6000f, 285.5f}, out);
    }

    @Test
    void nonFiniteValuesKeepTheFallback() {
        float[] result = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 300f};
        float[] fallback = {285f, 286f, 287f, 288f};
        float[] out = StepValidator.clean(result, fallback);
        assertEquals(285f, out[0], "NaN -> fallback");
        assertEquals(286f, out[1], "+Inf -> fallback");
        assertEquals(287f, out[2], "-Inf -> fallback");
        assertEquals(300f, out[3], "finite unchanged");
    }

    @Test
    void outOfRangeValuesAreClampedTo0_6000() {
        float[] result = {-50f, 9999f};
        float[] fallback = {1f, 1f};
        float[] out = StepValidator.clean(result, fallback);
        assertEquals(0f, out[0], "below 0 clamps to 0");
        assertEquals(6000f, out[1], "above 6000 clamps to 6000");
    }
}
