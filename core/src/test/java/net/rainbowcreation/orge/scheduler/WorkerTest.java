package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerTest {

    private static Worker worker(int initialRange) {
        return new Worker(UUID.randomUUID(), true, initialRange, 4, 250.0, 3);
    }

    @Test
    void lateDropsRangeByOneDownToMin() {
        Worker w = worker(3);
        w.reportLate();
        assertEquals(2, w.range());
        w.reportLate();
        assertEquals(1, w.range());
        w.reportLate();
        assertEquals(1, w.range(), "never below MIN_RANGE");
    }

    @Test
    void overBudgetCompletionDropsRangeAndResetsStreak() {
        Worker w = worker(3);
        w.noteStep(300.0, true); // on time but over the 250ms budget
        assertEquals(2, w.range());
        assertEquals(0, w.onTimeStreak());
    }

    @Test
    void missedDeadlineCompletionDropsRange() {
        Worker w = worker(3);
        w.noteStep(10.0, false); // fast but missed the deadline (completed during grace)
        assertEquals(2, w.range());
    }

    @Test
    void climbsAfterKOnTimeUnderBudgetSteps() {
        Worker w = worker(2);
        w.noteStep(10.0, true);
        assertEquals(2, w.range(), "streak 1 < 3");
        w.noteStep(10.0, true);
        assertEquals(2, w.range(), "streak 2 < 3");
        w.noteStep(10.0, true);
        assertEquals(3, w.range(), "streak reached 3 -> climb");
        assertEquals(0, w.onTimeStreak(), "streak resets after a climb");
    }

    @Test
    void neverClimbsAboveMax() {
        Worker w = worker(4);
        for (int i = 0; i < 6; i++) w.noteStep(10.0, true);
        assertEquals(4, w.range());
        assertEquals(0, w.onTimeStreak(), "streak still resets even at the cap");
    }

    @Test
    void exactlyAtBudgetIsHealthy() {
        Worker w = worker(2);
        w.noteStep(250.0, true); // millis == budget -> NOT over -> healthy
        assertEquals(1, w.onTimeStreak(), "at-budget advances the streak");
        assertEquals(2, w.range(), "at-budget does not drop range");
    }

    @Test
    void constructorClampsInvalidArguments() {
        // maxRange below MIN, initialRange above (clamped) max, ticksToClimb <= 0.
        Worker w = new Worker(UUID.randomUUID(), true, 99, -5, 250.0, 0);
        assertEquals(Worker.MIN_RANGE, w.range(), "initialRange clamped into [MIN, maxRange]; maxRange clamped to MIN");
        // ticksToClimb clamped to >=1, so a single healthy step climbs (but range already at max=MIN here).
        w.noteStep(10.0, true);
        assertEquals(Worker.MIN_RANGE, w.range(), "already at the clamped max");
    }

    @Test
    void climbThenLateDropsBack() {
        Worker w = worker(2);
        w.noteStep(10.0, true);
        w.noteStep(10.0, true);
        w.noteStep(10.0, true); // climbs to 3, streak reset
        assertEquals(3, w.range());
        w.reportLate();
        assertEquals(2, w.range(), "late drops back");
        assertEquals(0, w.onTimeStreak());
    }

    @Test
    void serverFallbackFlagAndIdExposed() {
        UUID id = UUID.randomUUID();
        Worker w = new Worker(id, true, 2, 4, 250.0, 3);
        assertTrue(w.isServerFallback());
        assertEquals(id, w.id());
    }
}
