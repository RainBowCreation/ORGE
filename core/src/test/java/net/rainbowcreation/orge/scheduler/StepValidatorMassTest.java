package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepValidatorMassTest {
    @Test
    void acceptsConservedMassWithinEpsilon() {
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 400f; after[1] = 600f; // moved 100 kg between two cells
        assertTrue(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void rejectsNonConservedMass() {
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 5000f; // 4500 kg created out of nothing
        assertFalse(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void rejectsCellAboveFullMassBound() {
        float[] before = new float[4096]; float[] after = new float[4096];
        // total conserved but one cell exceeds full mass (1000 kg) + epsilon.
        before[0] = 1000f; after[0] = 1000f; after[1] = -0.0f;
        after[0] = 1200f; after[1] = -200f;
        assertFalse(StepValidator.massConserved(after, before, 1000f));
    }
}
