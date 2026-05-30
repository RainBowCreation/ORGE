package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SphereUnionTest {

    @Test
    void rangeOneIsTheAnchorOnly() {
        Set<SubchunkKey> out = SphereUnion.expand(Set.of(new SubchunkKey(0, 0, 0)), 1);
        assertEquals(Set.of(new SubchunkKey(0, 0, 0)), out);
    }

    @Test
    void rangeTwoIsAnchorPlusSixFaceNeighbours() {
        Set<SubchunkKey> out = SphereUnion.expand(Set.of(new SubchunkKey(0, 0, 0)), 2);
        assertEquals(7, out.size());
        assertTrue(out.contains(new SubchunkKey(0, 0, 0)));
        assertTrue(out.contains(new SubchunkKey(1, 0, 0)));
        assertTrue(out.contains(new SubchunkKey(-1, 0, 0)));
        assertTrue(out.contains(new SubchunkKey(0, 1, 0)));
        assertTrue(out.contains(new SubchunkKey(0, -1, 0)));
        assertTrue(out.contains(new SubchunkKey(0, 0, 1)));
        assertTrue(out.contains(new SubchunkKey(0, 0, -1)));
        assertFalse(out.contains(new SubchunkKey(1, 1, 0)), "diagonal excluded at range 2");
    }

    @Test
    void rangeThreeHas33Sections() {
        Set<SubchunkKey> out = SphereUnion.expand(Set.of(new SubchunkKey(0, 0, 0)), 3);
        assertEquals(33, out.size());
        assertTrue(out.contains(new SubchunkKey(1, 1, 0)), "face-diagonal included at range 3");
        assertTrue(out.contains(new SubchunkKey(1, 1, 1)), "corner (d2=3) included at range 3");
        assertTrue(out.contains(new SubchunkKey(2, 0, 0)), "axis-double (d2=4) included at range 3");
        assertFalse(out.contains(new SubchunkKey(2, 1, 0)), "(d2=5) excluded at range 3");
    }

    @Test
    void overlappingAnchorsAreDeduped() {
        Set<SubchunkKey> a = SphereUnion.expand(
                Set.of(new SubchunkKey(0, 0, 0), new SubchunkKey(1, 0, 0)), 2);
        assertEquals(12, a.size(), "two adjacent range-2 spheres share 2 sections -> 12 unique");
        assertTrue(a.contains(new SubchunkKey(0, 0, 0)));
        assertTrue(a.contains(new SubchunkKey(1, 0, 0)));
    }

    @Test
    void emptyAnchorsYieldEmptySet() {
        assertEquals(Set.of(), SphereUnion.expand(Set.of(), 5));
    }

    @Test
    void handlesNegativeCoordinates() {
        Set<SubchunkKey> out = SphereUnion.expand(Set.of(new SubchunkKey(-5, -2, -8)), 2);
        assertTrue(out.contains(new SubchunkKey(-5, -2, -8)));
        assertTrue(out.contains(new SubchunkKey(-6, -2, -8)));
        assertEquals(7, out.size());
    }
}
