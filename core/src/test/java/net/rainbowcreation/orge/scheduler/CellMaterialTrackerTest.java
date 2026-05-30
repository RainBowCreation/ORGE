package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link CellMaterialTracker}: record/prior round-trip and per-column / global eviction. */
class CellMaterialTrackerTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier OTHER = Identifier.fromNamespaceAndPath("minecraft", "the_nether");

    private static Identifier[] ids(String tag) {
        Identifier[] a = new Identifier[net.rainbowcreation.orge.section.SectionData.CELLS];
        java.util.Arrays.fill(a, Identifier.fromNamespaceAndPath("orge", tag));
        return a;
    }

    @Test
    void priorIsNullBeforeRecord() {
        CellMaterialTracker t = new CellMaterialTracker();
        assertNull(t.prior(DIM, new SubchunkKey(0, 4, 0)));
    }

    @Test
    void recordThenPriorReturnsSameArray() {
        CellMaterialTracker t = new CellMaterialTracker();
        SubchunkKey key = new SubchunkKey(1, 4, -2);
        Identifier[] sig = ids("water");
        t.record(DIM, key, sig);
        assertSame(sig, t.prior(DIM, key));
    }

    @Test
    void distinctSectionsAndDimensionsAreIndependent() {
        CellMaterialTracker t = new CellMaterialTracker();
        SubchunkKey a = new SubchunkKey(0, 4, 0);
        SubchunkKey b = new SubchunkKey(0, 5, 0); // same column, different sectionY
        t.record(DIM, a, ids("water"));
        t.record(DIM, b, ids("lava"));
        assertEquals("orge:water", t.prior(DIM, a)[0].toString());
        assertEquals("orge:lava", t.prior(DIM, b)[0].toString());
        assertNull(t.prior(OTHER, a), "other dimension is independent");
    }

    @Test
    void forgetColumnDropsAllSectionsOfThatColumnOnly() {
        CellMaterialTracker t = new CellMaterialTracker();
        SubchunkKey a = new SubchunkKey(3, 4, 7);
        SubchunkKey b = new SubchunkKey(3, 9, 7); // same column
        SubchunkKey other = new SubchunkKey(4, 4, 7); // different column
        t.record(DIM, a, ids("water"));
        t.record(DIM, b, ids("water"));
        t.record(DIM, other, ids("water"));

        t.forgetColumn(DIM, 3, 7);

        assertNull(t.prior(DIM, a), "column (3,7) section 4 evicted");
        assertNull(t.prior(DIM, b), "column (3,7) section 9 evicted");
        assertNotNull(t.prior(DIM, other), "column (4,7) untouched");
    }

    @Test
    void clearDropsEverything() {
        CellMaterialTracker t = new CellMaterialTracker();
        t.record(DIM, new SubchunkKey(0, 4, 0), ids("water"));
        t.clear();
        assertNull(t.prior(DIM, new SubchunkKey(0, 4, 0)));
    }
}
