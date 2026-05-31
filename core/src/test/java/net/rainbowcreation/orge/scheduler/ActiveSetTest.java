package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ActiveSetTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final SubchunkKey K = new SubchunkKey(1, 4, 2);

    @Test
    void unseenSectionInRangeStartsActive() {
        ActiveSet a = new ActiveSet();
        // new-in-range: a key the set has never seen is treated active so it gets one settle step.
        List<SubchunkKey> stepped = a.activeWithin(DIM, List.of(K));
        assertEquals(List.of(K), stepped, "a never-seen in-range section is stepped (starts active)");
    }

    @Test
    void quietSectionFallsAsleepAndLeavesTheActiveSet() {
        ActiveSet a = new ActiveSet();
        // bring it into tracking (new-in-range), then feed K quiet flow + K quiet thermal steps.
        a.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) {
            a.noteFlowDelta(DIM, K, 0f);
            a.noteThermalDelta(DIM, K, 0f);
        }
        assertTrue(a.isAsleep(DIM, K), "both passes dormant");
        // an asleep section is NOT stepped even though it is in range.
        assertEquals(List.of(), a.activeWithin(DIM, List.of(K)));
    }

    @Test
    void flowDormantButThermalLiveStillStepsForThermal() {
        ActiveSet a = new ActiveSet();
        a.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) a.noteFlowDelta(DIM, K, 0f);
        assertTrue(a.isFlowDormant(DIM, K));
        assertFalse(a.isThermalDormant(DIM, K));
        // still in the active set because thermal is live.
        assertEquals(List.of(K), a.activeWithin(DIM, List.of(K)));
    }

    // ---- WAKE TRIGGERS (must be exhaustive) ----

    @Test
    void wakeFlowRevivesAnAsleepSection() {
        ActiveSet a = new ActiveSet();
        a.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { a.noteFlowDelta(DIM, K, 0f); a.noteThermalDelta(DIM, K, 0f); }
        assertTrue(a.isAsleep(DIM, K));
        a.wakeFlow(DIM, K);                      // trigger (a)/(c): edit / seam flux
        assertFalse(a.isFlowDormant(DIM, K));
        assertEquals(List.of(K), a.activeWithin(DIM, List.of(K)));
    }

    @Test
    void wakeThermalRevivesTheThermalPassOnly() {
        ActiveSet a = new ActiveSet();
        a.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { a.noteFlowDelta(DIM, K, 0f); a.noteThermalDelta(DIM, K, 0f); }
        a.wakeThermal(DIM, K);                   // trigger (b): source-roster change
        assertFalse(a.isThermalDormant(DIM, K));
        assertTrue(a.isFlowDormant(DIM, K), "flow stays dormant on a thermal-only wake");
    }

    @Test
    void wakeByBlockPosResolvesToTheOwningSectionAndWakesBoth() {
        ActiveSet a = new ActiveSet();
        // block (20, 70, 35) -> section (cx=1, sectionY=4, cz=2)  (>>4 each)
        SubchunkKey owner = new SubchunkKey(20 >> 4, 70 >> 4, 35 >> 4);
        a.activeWithin(DIM, List.of(owner));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { a.noteFlowDelta(DIM, owner, 0f); a.noteThermalDelta(DIM, owner, 0f); }
        assertTrue(a.isAsleep(DIM, owner));
        a.wakeBlock(DIM, 20, 70, 35);            // trigger (a)/(d): block placed/broken/bucket
        assertFalse(a.isAsleep(DIM, owner));
    }

    @Test
    void forgetColumnDropsTrackingForThatColumn() {
        ActiveSet a = new ActiveSet();
        a.activeWithin(DIM, List.of(K));
        a.forgetColumn(DIM, K.cx(), K.cz());
        // after forget, the key is "unseen" again -> starts active when next in range.
        assertEquals(List.of(K), a.activeWithin(DIM, List.of(K)));
    }
}
