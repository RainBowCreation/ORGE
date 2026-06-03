package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
