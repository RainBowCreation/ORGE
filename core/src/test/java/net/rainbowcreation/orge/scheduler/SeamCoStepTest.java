package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure headless tests for {@link SeamCoStep#expand}.
 * No Minecraft, no side effects — just list-in / list-out.
 */
class SeamCoStepTest {

    // ------- helpers -------

    /** A predicate that considers every key flow-active. */
    private static java.util.function.Predicate<SubchunkKey> allActive() {
        return k -> true;
    }

    /** A predicate that considers every key flow-dormant (not flow-active). */
    private static java.util.function.Predicate<SubchunkKey> allDormant() {
        return k -> false;
    }

    // ------- tests -------

    @Test
    void singleFlowActiveSection_returnsOriginalPlusSixNeighbours() {
        SubchunkKey k = new SubchunkKey(0, 0, 0);
        List<SubchunkKey> result = SeamCoStep.expand(List.of(k), allActive());

        // 1 original + 6 face-neighbours = 7
        assertEquals(7, result.size(), "expected 1 original + 6 face-neighbours");

        // original is present
        assertTrue(result.contains(k));

        // all 6 face-neighbours are present
        assertTrue(result.contains(new SubchunkKey(1,  0,  0)));
        assertTrue(result.contains(new SubchunkKey(-1, 0,  0)));
        assertTrue(result.contains(new SubchunkKey(0,  1,  0)));
        assertTrue(result.contains(new SubchunkKey(0, -1,  0)));
        assertTrue(result.contains(new SubchunkKey(0,  0,  1)));
        assertTrue(result.contains(new SubchunkKey(0,  0, -1)));
    }

    @Test
    void singleFlowActiveSection_originalComesFirst() {
        SubchunkKey k = new SubchunkKey(5, 3, -2);
        List<SubchunkKey> result = SeamCoStep.expand(List.of(k), allActive());

        assertEquals(k, result.get(0), "original key must be first in the output");
    }

    @Test
    void flowDormantSection_noNeighboursAdded() {
        SubchunkKey k = new SubchunkKey(3, 2, 1);
        List<SubchunkKey> result = SeamCoStep.expand(List.of(k), allDormant());

        // predicate returns false → no expansion; only the original
        assertEquals(1, result.size());
        assertEquals(k, result.get(0));
    }

    @Test
    void twoAdjacentFlowActiveSections_sharedNeighbourDeduped() {
        // (0,0,0) and (1,0,0) are adjacent; their shared face-neighbours include each other
        // and the section (0,0,0)→POS_X = (1,0,0) is already an original.
        SubchunkKey a = new SubchunkKey(0, 0, 0);
        SubchunkKey b = new SubchunkKey(1, 0, 0);
        List<SubchunkKey> result = SeamCoStep.expand(List.of(a, b), allActive());

        // Verify no duplicates
        long distinct = result.stream().distinct().count();
        assertEquals(distinct, result.size(), "no key may appear more than once");

        // Both originals present
        assertTrue(result.contains(a));
        assertTrue(result.contains(b));
    }

    @Test
    void neighbourAlreadyInActiveBatch_notDuplicated() {
        // (0,0,0) is flow-active; (1,0,0) is also in active (and is the POS_X neighbour of (0,0,0))
        SubchunkKey origin = new SubchunkKey(0, 0, 0);
        SubchunkKey neighbour = new SubchunkKey(1, 0, 0);
        List<SubchunkKey> active = List.of(origin, neighbour);

        // neighbour is flow-dormant so it won't add its own neighbours; origin is flow-active
        java.util.function.Predicate<SubchunkKey> pred = k -> k.equals(origin);
        List<SubchunkKey> result = SeamCoStep.expand(active, pred);

        long occurrences = result.stream().filter(neighbour::equals).count();
        assertEquals(1, occurrences, "neighbour already in active must appear exactly once");
    }

    @Test
    void mixed_onlyFlowActiveSectionsContributeNeighbours() {
        SubchunkKey flowActive   = new SubchunkKey(0, 0, 0);
        SubchunkKey flowDormant  = new SubchunkKey(10, 0, 0); // far away, no shared neighbours
        List<SubchunkKey> active = List.of(flowActive, flowDormant);

        java.util.function.Predicate<SubchunkKey> pred = k -> k.equals(flowActive);
        List<SubchunkKey> result = SeamCoStep.expand(active, pred);

        // flowActive contributes 6 neighbours; flowDormant contributes none
        // total = 2 originals + 6 neighbours (no overlap since flowDormant is far away)
        assertEquals(8, result.size());

        // flowDormant itself is present (it's an original)
        assertTrue(result.contains(flowDormant));

        // No neighbours of flowDormant (e.g. (11,0,0)) should be in result
        assertFalse(result.contains(new SubchunkKey(11, 0, 0)));
        assertFalse(result.contains(new SubchunkKey(9,  0, 0)));
    }

    @Test
    void determinism_sameInputSameOutputOrder() {
        SubchunkKey a = new SubchunkKey(0, 0, 0);
        SubchunkKey b = new SubchunkKey(5, 0, 0);
        List<SubchunkKey> input = List.of(a, b);

        List<SubchunkKey> run1 = SeamCoStep.expand(input, allActive());
        List<SubchunkKey> run2 = SeamCoStep.expand(input, allActive());

        assertEquals(run1, run2, "expand must be deterministic: same input → same order");
    }

    @Test
    void originalsPreservedFirst_neighboursAppendedAfter() {
        SubchunkKey a = new SubchunkKey(0, 0, 0);
        SubchunkKey b = new SubchunkKey(5, 5, 5);
        List<SubchunkKey> result = SeamCoStep.expand(List.of(a, b), allActive());

        // First two positions must be the originals in input order
        assertEquals(a, result.get(0), "first original first");
        assertEquals(b, result.get(1), "second original second");

        // All positions after index 1 must be neighbours (not one of the originals unless they
        // coincidentally neighbour each other — but (0,0,0) and (5,5,5) are far apart)
        List<SubchunkKey> added = result.subList(2, result.size());
        assertFalse(added.isEmpty(), "neighbours should have been added for both flow-active keys");
    }

    @Test
    void emptyActive_returnsEmpty() {
        List<SubchunkKey> result = SeamCoStep.expand(List.of(), allActive());
        assertTrue(result.isEmpty());
    }
}
