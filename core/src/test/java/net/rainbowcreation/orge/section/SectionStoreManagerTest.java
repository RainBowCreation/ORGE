package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for {@link SectionStoreManager}. No loader/server classes — the
 * manager is exercised purely via (Identifier dim, Path levelDir) like the loader
 * hooks would call it. Uses a real {@link Identifier} as the dimension key.
 */
class SectionStoreManagerTest {

    private static final Identifier OVERWORLD =
            Identifier.fromNamespaceAndPath("minecraft", "overworld");

    private static final AmbientProvider AMBIENT_300 = key -> 300.0f;

    /**
     * Full round trip: load level, load a chunk, write a section, unload the chunk
     * (flush to disk), then a brand-new manager re-loads the same level + chunk and
     * reads the persisted 500K/1000kg section back from {@code dir/orge/}.
     */
    @Test
    void persistsSectionAcrossManagerInstances(@TempDir Path dir) {
        SubchunkKey key = new SubchunkKey(0, 0, 0);

        SectionStoreManager first = new SectionStoreManager();
        first.onLevelLoad(OVERWORLD, dir, AMBIENT_300);
        first.onChunkLoad(OVERWORLD, 0, 0);
        first.store(OVERWORLD).put(key, SectionData.uniform(500.0f, 1000.0f));
        first.onChunkUnload(OVERWORLD, 0, 0); // flushes the dirty column to disk

        // A fresh manager (cold caches) must read the section back from disk.
        SectionStoreManager second = new SectionStoreManager();
        second.onLevelLoad(OVERWORLD, dir, AMBIENT_300);
        second.onChunkLoad(OVERWORLD, 0, 0);

        SectionData reloaded = second.store(OVERWORLD).get(key);
        assertEquals(500.0f, reloaded.temperatureAt(0), 0.0f, "temperature persisted");
        assertEquals(1000.0f, reloaded.massAt(0), 0.0f, "mass persisted");
    }

    /** An absent section materializes from the ambient provider (300 K here). */
    @Test
    void absentSectionReturnsAmbient(@TempDir Path dir) {
        SectionStoreManager m = new SectionStoreManager();
        m.onLevelLoad(OVERWORLD, dir, AMBIENT_300);
        m.onChunkLoad(OVERWORLD, 0, 0);

        SectionData ambient = m.store(OVERWORLD).get(new SubchunkKey(7, 3, 9));
        assertEquals(300.0f, ambient.temperatureAt(0), 0.0f, "ambient temperature");
    }

    /** Hooks for a never-loaded dimension must be silent no-ops, never throwing. */
    @Test
    void unknownDimensionGuardsDoNotThrow() {
        SectionStoreManager m = new SectionStoreManager();
        Identifier nether = Identifier.fromNamespaceAndPath("minecraft", "the_nether");

        assertNull(m.store(nether), "no store for an unloaded dimension");
        assertDoesNotThrow(() -> {
            m.onChunkLoad(nether, 0, 0);
            m.onChunkUnload(nether, 0, 0);
            m.onLevelSave(nether);
            m.onLevelUnload(nether);
        });
    }

    /** onLevelLoad is idempotent: a second call for the same dim keeps the live store. */
    @Test
    void levelLoadIsIdempotent(@TempDir Path dir) {
        SectionStoreManager m = new SectionStoreManager();
        m.onLevelLoad(OVERWORLD, dir, AMBIENT_300);
        SectionStore store = m.store(OVERWORLD);
        store.put(new SubchunkKey(0, 0, 0), SectionData.uniform(400.0f, 0.0f));

        m.onLevelLoad(OVERWORLD, dir, AMBIENT_300); // must NOT replace the live store

        assertEquals(store, m.store(OVERWORLD), "store identity preserved on re-load");
        assertEquals(400.0f, m.store(OVERWORLD).get(new SubchunkKey(0, 0, 0)).temperatureAt(0), 0.0f);
    }
}
