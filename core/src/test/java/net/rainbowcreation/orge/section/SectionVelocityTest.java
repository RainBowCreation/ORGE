package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the per-cell EXTENSIVE momentum channel (law §7). The channel stores momentum
 * {@code p [kg·m/s]}, never raw velocity; velocity {@code v = p/mass} is derived at the boundary.
 */
class SectionVelocityTest {
    @Test
    void momentumDefaultsToZeroAndPersistsPerCell() {
        SectionData s = SectionData.uniform(300_000f, 1000f);
        assertEquals(0f, s.momXAt(0), "momentum defaults to 0 (resting)");
        s.setMomentum(42, 1.5f, -2.0f, 0.25f);   // promotes to FULL
        assertEquals(SectionData.Form.FULL, s.form(), "setMomentum promotes to FULL");
        assertEquals(1.5f, s.momXAt(42));
        assertEquals(-2.0f, s.momYAt(42));
        assertEquals(0.25f, s.momZAt(42));
        assertEquals(0f, s.momXAt(43), "other cells still resting");
    }

    @Test
    void momentumArraysPromoteOnDemandAndAreLive() {
        SectionData s = SectionData.uniform(300_000f, 1000f);
        float[] px = s.momXArray();
        assertEquals(SectionData.CELLS, px.length);
        px[7] = 3.0f;
        assertEquals(3.0f, s.momXAt(7), "momXArray is the live backing array");
    }
}
