package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettleCountdownTest {

    @Test
    void freshSectionIsFullyActive() {
        SettleCountdown c = SettleCountdown.active();
        assertFalse(c.flowDormant());
        assertFalse(c.thermalDormant());
        assertFalse(c.asleep());
    }

    @Test
    void flowGoesDormantAfterKQuietAdvectionSteps() {
        SettleCountdown c = SettleCountdown.active();
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) {
            assertFalse(c.flowDormant(), "still live before K quiet steps");
            c = c.noteFlow(0f); // delta below EPS_MASS
        }
        assertTrue(c.flowDormant(), "dormant after K consecutive quiet advection steps");
        assertFalse(c.thermalDormant(), "thermal pass independent — still live");
        assertFalse(c.asleep(), "not fully asleep while thermal is live");
    }

    @Test
    void aboveThresholdDeltaResetsTheFlowCountdown() {
        SettleCountdown c = SettleCountdown.active();
        c = c.noteFlow(0f).noteFlow(0f);            // 2 quiet steps
        c = c.noteFlow(SettleCountdown.EPS_MASS * 10f); // a real move resets
        for (int i = 0; i < SettleCountdown.K_SETTLE - 1; i++) c = c.noteFlow(0f);
        assertFalse(c.flowDormant(), "reset means it takes K more quiet steps");
        c = c.noteFlow(0f);
        assertTrue(c.flowDormant());
    }

    @Test
    void bothPassesDormantMeansAsleep() {
        SettleCountdown c = SettleCountdown.active();
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) c = c.noteFlow(0f);
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) c = c.noteThermal(0f);
        assertTrue(c.flowDormant());
        assertTrue(c.thermalDormant());
        assertTrue(c.asleep(), "both dormant -> fully asleep -> drop from schedule");
    }

    @Test
    void wakeFlowRevivesOnlyTheFlowPass() {
        SettleCountdown c = SettleCountdown.active();
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { c = c.noteFlow(0f); c = c.noteThermal(0f); }
        assertTrue(c.asleep());
        c = c.wakeFlow();
        assertFalse(c.flowDormant(), "flow revived");
        assertTrue(c.thermalDormant(), "thermal still dormant (per-pass wake)");
        assertFalse(c.asleep());
    }

    @Test
    void wakeAllRevivesBothPasses() {
        SettleCountdown c = SettleCountdown.active();
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { c = c.noteFlow(0f); c = c.noteThermal(0f); }
        assertTrue(c.asleep());
        c = c.wakeAll();
        assertFalse(c.flowDormant());
        assertFalse(c.thermalDormant());
    }
}
