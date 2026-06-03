package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** B2 durability: an intent stays queued across peeks/steps until explicitly removed (success). */
class SchedulerInjectionDurabilityTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");

    @Test
    void intentSurvivesUntilRemoved() {
        PendingInjections q = new PendingInjections();
        int cell = 3 + 16 * 70;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);

        // Three "stale" snapshot drains (peek) — intent must persist each time:
        for (int i = 0; i < 3; i++) {
            List<PendingInjections.Intent> drained = q.peekColumn(DIM, 0, 0);
            assertEquals(1, drained.size(), "still queued on stale step " + i);
            // simulate a HELD region: do NOT remove
        }
        // a successful write-back removes it:
        q.remove(q.peekColumn(DIM, 0, 0));
        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty(), "cleared after success");
    }
}
