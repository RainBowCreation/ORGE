package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionVelocityTest {
    @Test
    void velocityDefaultsToZeroAndPersistsPerCell() {
        SectionData s = SectionData.uniform(300f, 1000f);
        assertEquals(0f, s.velXAt(0), "velocity defaults to 0");
        s.setVelocity(42, 1.5f, -2.0f, 0.25f);   // promotes to FULL
        assertEquals(SectionData.Form.FULL, s.form(), "setVelocity promotes to FULL");
        assertEquals(1.5f, s.velXAt(42));
        assertEquals(-2.0f, s.velYAt(42));
        assertEquals(0.25f, s.velZAt(42));
        assertEquals(0f, s.velXAt(43), "other cells still 0");
    }

    @Test
    void velocityArraysPromoteOnDemandAndAreLive() {
        SectionData s = SectionData.uniform(300f, 1000f);
        float[] vx = s.velXArray();
        assertEquals(SectionData.CELLS, vx.length);
        vx[7] = 3.0f;
        assertEquals(3.0f, s.velXAt(7), "velXArray is the live backing array");
    }
}
