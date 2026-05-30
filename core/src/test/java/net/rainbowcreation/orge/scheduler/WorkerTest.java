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
    }
}
