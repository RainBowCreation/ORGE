package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableMap;

import static org.junit.jupiter.api.Assertions.*;

class SectionStoreTest {

    @TempDir
    Path world;

    /** A fake AmbientProvider that returns 300 K and 1.2 kg for any key. */
    private static final AmbientProvider AMB = new AmbientProvider() {
        @Override
        public float ambientTemperatureK(SubchunkKey k) { return 300f; }
        @Override
        public float ambientMassKg(SubchunkKey k) { return 1.2f; }
    };

    // -------------------------------------------------------------------------
    // Test 1: Materialize ambient — never stored, never persisted
    // -------------------------------------------------------------------------

    @Test
    void ambientMaterializationIsNotPersisted() {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        SubchunkKey key = new SubchunkKey(0, 0, 0);
        SectionData s = store.get(key);

        // Correct ambient values from AMB
        assertEquals(300f, s.temperatureAt(0), "ambient temperature must match AmbientProvider");
        assertEquals(1.2f, s.massAt(0),         "ambient mass must match AmbientProvider");
        assertEquals(SectionData.Form.UNIFORM, s.form(), "ambient section must be UNIFORM");

        // The ambient section must NOT have been persisted to disk
        NavigableMap<Integer, SectionData> onDisk = region.loadColumn(0, 0);
        assertTrue(onDisk.isEmpty(), "ambient materialization must not write to disk");

        // Calling get() again must yield a value-equal (but possibly distinct) section
        SectionData s2 = store.get(key);
        assertTrue(s.equalsValue(s2), "repeated ambient get must be value-equal");

        region.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 2: Put then get returns live in-memory value
    // -------------------------------------------------------------------------

    @Test
    void putThenGetReturnsLiveSection() {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        SubchunkKey key = new SubchunkKey(0, 0, 0);
        SectionData expected = SectionData.uniform(500f, 1000f);
        store.put(key, expected);

        assertTrue(store.get(key).equalsValue(expected),
                "get after put must return value-equal section");

        region.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 3: Dirty flush on unload — reload from disk on fresh store
    // -------------------------------------------------------------------------

    @Test
    void dirtyColumnFlushedOnUnloadAndReloadableFromDisk() throws IOException {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        SubchunkKey key = new SubchunkKey(0, 0, 0);
        // Build a FULL section so we exercise full serialization too
        SectionData expected = SectionData.uniform(500f, 1000f);
        expected.setTemperature(10, 600f);   // forces FULL form
        assertEquals(SectionData.Form.FULL, expected.form(), "section must be FULL after setTemperature");

        store.put(key, expected);
        store.unloadColumn(0, 0);

        // Close the region store so all file handles are released
        region.closeAll();

        // Reopen with a fresh RegionStore and SectionStore
        RegionStore region2 = new RegionStore(world);
        SectionStore store2 = new SectionStore(region2, AMB);
        store2.loadColumn(0, 0);

        SectionData loaded = store2.get(key);
        assertTrue(expected.equalsValue(loaded),
                "section reloaded from disk must equal the put section (not ambient)");

        region2.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 4: Clean column produces no region file; ambient reads also do not dirty
    // -------------------------------------------------------------------------

    @Test
    void cleanColumnAndAmbientReadsProduceNoFile() throws IOException {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        // Load chunk 5,5 (region 0,0) but don't put anything
        store.loadColumn(5, 5);

        // A get() on an absent section (ambient materialization) must not dirty
        store.get(new SubchunkKey(5, 0, 5));

        // Unload without ever having put data
        store.unloadColumn(5, 5);

        Path regionFile = world.resolve("orge").resolve("r.0.0.orge");
        assertFalse(Files.exists(regionFile),
                "no region file must be created for a clean (never-put) column");

        region.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 5: flushAll persists multiple columns AND keeps them loaded
    // -------------------------------------------------------------------------

    @Test
    void flushAllPersistsMultipleColumnsAndKeepsThem() throws IOException {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        SubchunkKey key00 = new SubchunkKey(0, 0, 0);
        SubchunkKey key10 = new SubchunkKey(10, 2, 10);

        SectionData sec00 = SectionData.uniform(400f, 800f);
        // Make a FULL section for column (10,10) to exercise FULL serialization
        SectionData sec10 = SectionData.uniform(350f, 500f);
        sec10.setTemperature(5, 999f);  // forces FULL form
        assertEquals(SectionData.Form.FULL, sec10.form());

        store.put(key00, sec00);
        store.put(key10, sec10);

        store.flushAll();

        // After flushAll the original store must still have both columns in memory
        assertTrue(store.get(key00).equalsValue(sec00),
                "flushAll must NOT evict column (0,0) from memory");
        assertTrue(store.get(key10).equalsValue(sec10),
                "flushAll must NOT evict column (10,10) from memory");

        // Close and reopen to verify both columns persisted to disk
        region.closeAll();
        RegionStore region2 = new RegionStore(world);
        SectionStore store2 = new SectionStore(region2, AMB);

        store2.loadColumn(0, 0);
        store2.loadColumn(10, 10);

        assertTrue(store2.get(key00).equalsValue(sec00),
                "column (0,0) must reload correctly after flushAll");
        assertTrue(store2.get(key10).equalsValue(sec10),
                "column (10,10) must reload correctly after flushAll");

        region2.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 6: loadColumn is idempotent — does not clobber live state
    // -------------------------------------------------------------------------

    @Test
    void loadColumnIsIdempotentDoesNotClobberLiveState() {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        SubchunkKey key = new SubchunkKey(0, 4, 0);
        SectionData live = SectionData.uniform(777f, 42f);

        // First load (fresh column from disk — empty)
        store.loadColumn(0, 0);
        // Write live data into memory
        store.put(key, live);

        // Calling loadColumn again must NOT overwrite the live in-memory value
        store.loadColumn(0, 0);

        assertTrue(store.get(key).equalsValue(live),
                "loadColumn after put must not clobber live in-memory data (idempotent)");

        region.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 7: FALLBACK constant uses DEFAULT_AMBIENT_K, zero mass
    // -------------------------------------------------------------------------

    @Test
    void fallbackConstantReturnsDefaultAmbient() {
        SubchunkKey key = new SubchunkKey(7, -3, 12);
        assertEquals(SectionData.DEFAULT_AMBIENT_K,
                AmbientProvider.FALLBACK.ambientTemperatureK(key),
                "FALLBACK temperature must equal DEFAULT_AMBIENT_K");
        assertEquals(0f,
                AmbientProvider.FALLBACK.ambientMassKg(key),
                "FALLBACK mass must be 0");
    }

    // -------------------------------------------------------------------------
    // Test 9: hasSection — stored-vs-ambient probe
    // -------------------------------------------------------------------------

    @Test
    void hasSectionFalseUntilPut() {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);
        store.loadColumn(0, 0);
        SubchunkKey key = new SubchunkKey(0, 4, 0);
        assertFalse(store.hasSection(key), "never-written section is not stored");
        store.put(key, SectionData.uniform(400f, 0f));
        assertTrue(store.hasSection(key), "after put it is stored");
        assertFalse(store.hasSection(new SubchunkKey(0, 5, 0)), "sibling section still absent");
        region.closeAll();
    }

    // -------------------------------------------------------------------------
    // Test 8: flushAll correctly round-trips negative chunk coordinates
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Task D3: SectionStore is the per-cell material authority
    // -------------------------------------------------------------------------

    private static Identifier matId(String p) {
        return Identifier.fromNamespaceAndPath("orge", p);
    }

    @Test
    void setMaterialAt_thenMaterialAt_returnsStoredId() {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        store.setMaterialAt(0, 0, 3, 5, matId("water"));
        assertEquals(matId("water"), store.materialAt(0, 0, 3, 5),
                "materialAt must return the id written by setMaterialAt");

        region.closeAll();
    }

    @Test
    void materialAt_neverWritten_returnsVacuum() {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        // Materialize the section by writing one cell, then probe a sibling cell.
        store.setMaterialAt(0, 0, 3, 5, matId("water"));
        assertEquals(MaterialPalette.VACUUM_ID, store.materialAt(0, 0, 3, 6),
                "unwritten cell in a stored section reads vacuum");

        // A wholly unstored section reads vacuum without synthesizing/storing anything.
        assertEquals(MaterialPalette.VACUUM_ID, store.materialAt(0, 0, 99, 0),
                "unstored section reads vacuum");
        assertFalse(store.hasSection(new SubchunkKey(0, 99, 0)),
                "materialAt read must not store the section");

        region.closeAll();
    }

    @Test
    void setMaterialAt_survivesUnloadReloadRoundTrip() throws IOException {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        store.setMaterialAt(0, 0, 3, 5, matId("water"));
        store.unloadColumn(0, 0);
        region.closeAll();

        // Fresh stores on the same world dir
        RegionStore region2 = new RegionStore(world);
        SectionStore store2 = new SectionStore(region2, AMB);
        store2.loadColumn(0, 0);

        assertEquals(matId("water"), store2.materialAt(0, 0, 3, 5),
                "material must survive demote + codec + save/load round-trip");

        region2.closeAll();
    }

    @Test
    void flushAll_negativeChunkCoords_roundTrips() {
        RegionStore region = new RegionStore(world);
        SectionStore store = new SectionStore(region, AMB);

        SubchunkKey key = new SubchunkKey(-1, 3, -1);
        SectionData expected = SectionData.uniform(412f, 900f);
        store.put(key, expected);

        store.flushAll();

        region.closeAll();

        // Reopen with fresh stores — proving the negative colKey packed and unpacked correctly
        RegionStore region2 = new RegionStore(world);
        SectionStore store2 = new SectionStore(region2, AMB);
        store2.loadColumn(-1, -1);

        assertTrue(store2.get(new SubchunkKey(-1, 3, -1)).equalsValue(SectionData.uniform(412f, 900f)),
                "flushAll must correctly round-trip sections stored at negative chunk coordinates");

        region2.closeAll();
    }
}
