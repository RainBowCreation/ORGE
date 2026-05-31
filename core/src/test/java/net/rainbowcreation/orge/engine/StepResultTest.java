package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepResultTest {

    @Test
    void stepResultCarriesMaterial() {
        char[] mat = new char[]{1, 0, 1};
        StepResult r = new StepResult(new float[]{300f, 0f, 300f}, new float[]{1000f, 0f, 1000f}, mat);
        assertArrayEquals(mat, r.material());
    }

    @Test
    void twoArgCtorHasNullMaterial() {
        StepResult r = new StepResult(new float[]{300f}, new float[]{1000f});
        assertNull(r.material());
    }
}
