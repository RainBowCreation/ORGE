package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepMetricsTest {

    @Test
    void emaSeedsThenSmooths() {
        StepMetrics m = new StepMetrics();
        m.onComplete(10, 4, 5, true, 0.25);
        assertEquals(10, m.nativeEmaMs, 1e-12, "first sample seeds the EMA");
        m.onComplete(20, 4, 5, true, 0.25);
        assertEquals(12, m.nativeEmaMs, 1e-12, "10 + 0.2*(20-10)");
        assertEquals(20, m.nativeMaxMs, 0.0);
        m.onComplete(0, 4, 7, false, 0.25);
        assertEquals(9.6, m.nativeEmaMs, 1e-12);
        assertEquals(3, m.completed);
        assertEquals(1, m.late);
    }

    @Test
    void ratioAndVerdict() {
        StepMetrics m = new StepMetrics();
        assertEquals(1.0, m.ratio(), 0.0, "no real time yet");
        for (int i = 0; i < 20; i++) m.onRealTick();             // 1 s real
        for (int i = 0; i < 4; i++) m.onComplete(10, 5, 5, true, 0.25); // 1 s sim
        assertEquals(1.0, m.ratio(), 1e-12);
        assertTrue(m.keepingUp(250, 1.0));
        assertFalse(m.keepingUp(14, 1.0), "EMA native+server over budget");
        for (int i = 0; i < 20; i++) m.onRealTick();             // 2 s real, 1 s sim
        assertEquals(0.5, m.ratio(), 1e-12);
        assertFalse(m.keepingUp(250, 1.0), "sim falling behind real time");
        assertTrue(m.keepingUp(250, 0.5), "matches a configured 0.5x time-scale");
        m.reset();
        assertEquals(0, m.completed);
        assertEquals(1.0, m.ratio(), 0.0);
    }
}
