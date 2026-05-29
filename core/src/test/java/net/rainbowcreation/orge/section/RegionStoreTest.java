package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class RegionStoreTest {

    @TempDir
    Path world;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a representative column with sectionY -4 (UNIFORM), 0 (FULL), 7 (UNIFORM). */
    private static NavigableMap<Integer, SectionData> buildTestColumn() {
        NavigableMap<Integer, SectionData> map = new TreeMap<>();
        map.put(-4, SectionData.uniform(285.0f, 1000.0f));

        // FULL section: start uniform then add a gradient so form == FULL
        SectionData full = SectionData.uniform(300.0f, 500.0f);
        for (int i = 0; i < SectionData.CELLS; i++) {
            full.setTemperature(i, 300.0f + i * 0.001f);
        }
        assertEquals(SectionData.Form.FULL, full.form());
        map.put(0, full);

        map.put(7, SectionData.uniform(273.0f, 0.0f));
        return map;
    }

    /** Assert two columns have the same keys and value-equal sections. */
    private static void assertColumnsEqual(
            NavigableMap<Integer, SectionData> expected,
            NavigableMap<Integer, SectionData> actual) {
        assertEquals(expected.keySet(), actual.keySet(), "column keySets differ");
        for (int key : expected.keySet()) {
            assertTrue(
                expected.get(key).equalsValue(actual.get(key)),
                "section at sectionY=" + key + " differs"
            );
        }
    }

    // -------------------------------------------------------------------------
    // Test 1: Column round-trip + persist across store reopen
    // -------------------------------------------------------------------------

    @Test
    void columnRoundTripAndPersistence() throws IOException {
        NavigableMap<Integer, SectionData> map = buildTestColumn();

        RegionStore store = new RegionStore(world);
        store.saveColumn(2, 3, map);

        NavigableMap<Integer, SectionData> loaded = store.loadColumn(2, 3);
        assertColumnsEqual(map, loaded);

        store.closeAll();

        // Reopen a fresh store — should read from disk
        RegionStore store2 = new RegionStore(world);
        NavigableMap<Integer, SectionData> persisted = store2.loadColumn(2, 3);
        assertColumnsEqual(map, persisted);
        store2.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 2: Absent column → empty (not null)
    // -------------------------------------------------------------------------

    @Test
    void absentColumnReturnsEmptyNotNull() throws IOException {
        RegionStore store = new RegionStore(world);
        NavigableMap<Integer, SectionData> result = store.loadColumn(100, 100);
        assertNotNull(result, "loadColumn must never return null");
        assertTrue(result.isEmpty(), "absent column must return empty map");
        store.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 3: loadColumn for a never-written region returns empty and creates no file
    // -------------------------------------------------------------------------

    @Test
    void loadColumnNeverWrittenRegionCreatesNoFile() {
        RegionStore store = new RegionStore(world);

        // Chunk (5, 5) lives in region (0, 0); no data has ever been written
        NavigableMap<Integer, SectionData> result = store.loadColumn(5, 5);

        assertNotNull(result, "loadColumn must never return null");
        assertTrue(result.isEmpty(), "absent column must return empty map");

        Path regionFile = world.resolve("orge").resolve("r.0.0.orge");
        assertFalse(Files.exists(regionFile),
                "loadColumn for a never-written region must NOT create the region file");

        store.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 4: Negative coords + correct file naming
    // -------------------------------------------------------------------------

    @Test
    void negativeCoordinatesAndFileNaming() throws IOException {
        NavigableMap<Integer, SectionData> map = buildTestColumn();
        NavigableMap<Integer, SectionData> map2 = new TreeMap<>();
        map2.put(3, SectionData.uniform(295.0f, 800.0f));

        RegionStore store = new RegionStore(world);
        store.saveColumn(-1, -1, map);

        // Check file exists at the expected path
        assertTrue(
            Files.exists(world.resolve("orge").resolve("r.-1.-1.orge")),
            "region file r.-1.-1.orge must exist"
        );

        // Round-trip for negative coords
        NavigableMap<Integer, SectionData> loaded = store.loadColumn(-1, -1);
        assertColumnsEqual(map, loaded);

        // Also save at (0,0) -> r.0.0.orge
        store.saveColumn(0, 0, map2);
        assertTrue(
            Files.exists(world.resolve("orge").resolve("r.0.0.orge")),
            "region file r.0.0.orge must exist"
        );

        store.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 5: Two columns share one region file
    // -------------------------------------------------------------------------

    @Test
    void twoColumnsShareOneRegionFile() throws IOException {
        NavigableMap<Integer, SectionData> a = new TreeMap<>();
        a.put(0, SectionData.uniform(285.0f, 1000.0f));

        NavigableMap<Integer, SectionData> b = new TreeMap<>();
        b.put(1, SectionData.uniform(300.0f, 500.0f));

        RegionStore store = new RegionStore(world);
        // Both (0,0) and (1,1) are in region (0,0)
        store.saveColumn(0, 0, a);
        store.saveColumn(1, 1, b);

        // Only one region file should exist
        assertTrue(
            Files.exists(world.resolve("orge").resolve("r.0.0.orge")),
            "r.0.0.orge must exist"
        );
        assertFalse(
            Files.exists(world.resolve("orge").resolve("r.1.1.orge")),
            "r.1.1.orge must NOT exist — both columns share r.0.0.orge"
        );

        // Each loads back independently
        assertColumnsEqual(a, store.loadColumn(0, 0));
        assertColumnsEqual(b, store.loadColumn(1, 1));

        store.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 6: Empty save deletes the slot
    // -------------------------------------------------------------------------

    @Test
    void emptySaveDeletesSlot() throws IOException {
        NavigableMap<Integer, SectionData> a = new TreeMap<>();
        a.put(0, SectionData.uniform(285.0f, 1000.0f));

        RegionStore store = new RegionStore(world);
        store.saveColumn(0, 0, a);

        // Overwrite with empty map — should delete the slot
        store.saveColumn(0, 0, new TreeMap<>());

        NavigableMap<Integer, SectionData> result = store.loadColumn(0, 0);
        assertNotNull(result, "loadColumn must never return null");
        assertTrue(result.isEmpty(), "after empty save, column must be empty");

        store.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 7: Per-section convenience (load/save delegates to column)
    // -------------------------------------------------------------------------

    @Test
    void perSectionConvenienceMethods() throws IOException {
        RegionStore store = new RegionStore(world);

        SectionData sec5 = SectionData.uniform(300.0f, 1000.0f);
        store.save(new SubchunkKey(2, 5, 3), sec5);

        // Load the section back
        SectionData loaded5 = store.load(new SubchunkKey(2, 5, 3));
        assertNotNull(loaded5, "loaded section must not be null");
        assertTrue(sec5.equalsValue(loaded5), "loaded section must match saved");

        // Section not present in column returns null
        SectionData loaded6 = store.load(new SubchunkKey(2, 6, 3));
        assertNull(loaded6, "absent section must return null");

        // Save a second section into the SAME column (read-modify-write must NOT drop first)
        SectionData sec6 = SectionData.uniform(310.0f, 900.0f);
        store.save(new SubchunkKey(2, 6, 3), sec6);

        // Both sections must be in the column
        NavigableMap<Integer, SectionData> col = store.loadColumn(2, 3);
        assertTrue(col.containsKey(5), "column must still contain sectionY=5 after second save");
        assertTrue(col.containsKey(6), "column must contain sectionY=6 after second save");
        assertTrue(sec5.equalsValue(col.get(5)), "sectionY=5 must be value-equal after second save");
        assertTrue(sec6.equalsValue(col.get(6)), "sectionY=6 must be value-equal after second save");

        store.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 8: close() implements Closeable
    // -------------------------------------------------------------------------

    @Test
    void closeIsIdempotentAndImplementsCloseable() throws IOException {
        RegionStore store = new RegionStore(world);
        NavigableMap<Integer, SectionData> a = new TreeMap<>();
        a.put(0, SectionData.uniform(285.0f, 1000.0f));
        store.saveColumn(0, 0, a);

        // Closeable.close() must work
        store.close();

        // closeAll() after close should be a no-op (open map is cleared)
        assertDoesNotThrow(store::closeAll);
    }

    // -------------------------------------------------------------------------
    // Test 9: directory() returns the correct subdirectory
    // -------------------------------------------------------------------------

    @Test
    void directoryReturnsOrgeSubdir() {
        RegionStore store = new RegionStore(world);
        assertEquals(world.resolve("orge"), store.directory());
    }
}
