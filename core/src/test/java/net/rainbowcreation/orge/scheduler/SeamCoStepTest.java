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

    // ------- §11 gas-column co-step (the section ABOVE an active fluid/gas surface) -------

    /** A predicate true for every key (used for "surface active everywhere"). */
    private static java.util.function.Predicate<SubchunkKey> all() { return k -> true; }

    /** A predicate false for every key. */
    private static java.util.function.Predicate<SubchunkKey> none() { return k -> false; }

    @Test
    void gasColumn_sectionAboveActiveSurfaceIsIncluded() {
        // A section with a fluid/gas surface but NOT itself flow-active (its own flow is dormant) must
        // still pull in the section directly ABOVE it so rising/displaced gas has a loaded receiver
        // across the Y seam. With the plain 6-face expand (flow-dormant) nothing would be added.
        SubchunkKey k = new SubchunkKey(2, 4, 7);
        List<SubchunkKey> result = SeamCoStep.expand(
                List.of(k),
                /* isFlowActive   */ none(),
                /* hasActiveSurface */ all(),
                /* sectionYWithinWorld */ y -> true);

        assertTrue(result.contains(new SubchunkKey(2, 5, 7)),
                "the section directly above an active surface must be co-stepped");
        // It is the ONLY thing added besides the original (not the full 6-face skirt).
        assertEquals(2, result.size(), "only the section above is added for a surface-only section");
        assertEquals(k, result.get(0), "original first");
    }

    @Test
    void gasColumn_notAddedAtWorldTopBoundary() {
        // The section above a top-of-world section does not exist (would be unloaded). The within-world
        // predicate gates it out so we never add a phantom out-of-world neighbour.
        SubchunkKey top = new SubchunkKey(0, 19, 0);
        List<SubchunkKey> result = SeamCoStep.expand(
                List.of(top),
                /* isFlowActive   */ none(),
                /* hasActiveSurface */ all(),
                /* sectionYWithinWorld */ y -> y <= 19); // 20 is above the world top

        assertFalse(result.contains(new SubchunkKey(0, 20, 0)),
                "section above the world-top must NOT be added");
        assertEquals(1, result.size(), "only the original survives at the world boundary");
    }

    @Test
    void gasColumn_dedupedWithFlowActiveSkirt() {
        // A flow-active section already adds its sy+1 neighbour via the 6-face skirt; the gas-column
        // pass must not duplicate it.
        SubchunkKey k = new SubchunkKey(0, 0, 0);
        List<SubchunkKey> result = SeamCoStep.expand(
                List.of(k),
                /* isFlowActive   */ all(),
                /* hasActiveSurface */ all(),
                /* sectionYWithinWorld */ y -> true);

        long above = result.stream().filter(new SubchunkKey(0, 1, 0)::equals).count();
        assertEquals(1, above, "the section above appears exactly once even though both passes add it");
        // 1 original + 6 face neighbours; the gas-column above (0,1,0) is already among the 6.
        assertEquals(7, result.size());
    }

    @Test
    void gasColumn_noSurfaceNoColumnAdded() {
        // No active surface anywhere and not flow-active -> nothing added (back to the dormant case).
        SubchunkKey k = new SubchunkKey(3, 3, 3);
        List<SubchunkKey> result = SeamCoStep.expand(
                List.of(k), none(), none(), y -> true);
        assertEquals(1, result.size());
        assertEquals(k, result.get(0));
    }

    @Test
    void twoArgExpand_unchanged_backCompat() {
        // The legacy 2-arg expand still behaves exactly as before (full 6-face skirt for flow-active).
        SubchunkKey k = new SubchunkKey(0, 0, 0);
        assertEquals(7, SeamCoStep.expand(List.of(k), allActive()).size());
        assertEquals(1, SeamCoStep.expand(List.of(k), allDormant()).size());
    }
}
