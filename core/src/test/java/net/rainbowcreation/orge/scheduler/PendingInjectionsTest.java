package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class PendingInjectionsTest {

    private static final Identifier DIM   = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier LAVA  = Identifier.fromNamespaceAndPath("orge", "lava");

    @Test
    void enqueueThenPeekColumnReturnsIntent() {
        PendingInjections q = new PendingInjections();
        int cell = 3 + 16 * 70 + 6144 * 4;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size());
        PendingInjections.Intent in = got.get(0);
        assertEquals(cell, in.cell());
        assertEquals(WATER, in.species());
        assertEquals(1000f, in.mass());
        assertEquals(290f, in.temperature());
        assertTrue(q.peekColumn(DIM, 1, 0).isEmpty(), "other column has none");
    }

    @Test
    void sameCellLastWriteWins() {            // Plan-1 carry-in: same-cell dedup lives here
        PendingInjections q = new PendingInjections();
        int cell = 5 + 16 * 70 + 6144 * 4;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);
        q.enqueue(DIM, 0, 0, cell, LAVA, 3000f, 1500f);   // overwrites the same (dim,cell) key

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size(), "same cell collapses to one intent");
        assertEquals(LAVA, got.get(0).species());
        assertEquals(3000f, got.get(0).mass());
    }

    @Test
    void hasPendingRemovalDetectsOnlyAQueuedRemovalAtThatCell() {
        PendingInjections q = new PendingInjections();
        int cell = 4 + 16 * 70 + 6144 * 4;
        assertFalse(q.hasPendingRemoval(DIM, 0, 0, cell), "empty queue: no pending removal");

        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);
        assertFalse(q.hasPendingRemoval(DIM, 0, 0, cell), "a placement intent is not a removal");

        q.enqueueRemoval(DIM, 0, 0, cell);
        assertTrue(q.hasPendingRemoval(DIM, 0, 0, cell), "a queued removal is detected");
        assertFalse(q.hasPendingRemoval(DIM, 0, 0, cell + 1), "a different cell is unaffected");
        assertFalse(q.hasPendingRemoval(DIM, 1, 0, cell), "a different column is unaffected");

        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);   // a place supersedes the removal (same key)
        assertFalse(q.hasPendingRemoval(DIM, 0, 0, cell),
                "a placement overwriting the removal is no longer a pending removal");
    }

    @Test
    void removeClearsOnlyTheGivenIntents() {
        PendingInjections q = new PendingInjections();
        int a = 1 + 16 * 70, b = 2 + 16 * 70;
        q.enqueue(DIM, 0, 0, a, WATER, 1000f, 290f);
        q.enqueue(DIM, 0, 0, b, WATER, 1000f, 290f);
        List<PendingInjections.Intent> drained = q.peekColumn(DIM, 0, 0);

        q.remove(List.of(drained.get(0)));                 // simulate one applied
        List<PendingInjections.Intent> left = q.peekColumn(DIM, 0, 0);
        assertEquals(1, left.size(), "the un-removed intent stays queued (durability)");
        assertEquals(drained.get(1).cell(), left.get(0).cell());
    }

    @Test
    void peekDoesNotRemove() {
        PendingInjections q = new PendingInjections();
        int cell = 7 + 16 * 70;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);
        q.peekColumn(DIM, 0, 0);
        assertEquals(1, q.peekColumn(DIM, 0, 0).size(), "peek leaves the intent (survives stale steps)");
    }

    @Test
    void removeDoesNotDropANewerSameCellIntent() {   // vanish-race durability
        PendingInjections q = new PendingInjections();
        int cell = 9 + 16 * 70 + 6144 * 4;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);          // intent A
        List<PendingInjections.Intent> drainedA = q.peekColumn(DIM, 0, 0);   // holds A
        q.enqueue(DIM, 0, 0, cell, LAVA, 3000f, 1500f);          // intent B overwrites the cell (not yet applied)

        q.remove(drainedA);                                       // write-back of the A-step

        List<PendingInjections.Intent> left = q.peekColumn(DIM, 0, 0);
        assertEquals(1, left.size(), "newer same-cell intent B survives removal of A");
        assertEquals(LAVA, left.get(0).species(), "the un-applied newer placement is kept");
    }
}
