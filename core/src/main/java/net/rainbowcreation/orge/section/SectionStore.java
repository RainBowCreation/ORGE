package net.rainbowcreation.orge.section;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Live in-memory authority for {@link SectionData} — the source of truth other
 * subsystems (scheduler, engine) read and write each tick (DESIGN.md §5).
 *
 * <p>Loaded chunk columns sit in memory. A section that has never been stored
 * materializes <em>on demand</em> as an ambient {@code UNIFORM} section — without
 * being inserted into storage — so unsimulated regions cost nothing. The biome
 * temperature and material default-mass used for ambient materialization are supplied
 * by the injected {@link AmbientProvider} seam.</p>
 *
 * <p>When a chunk column unloads (or on autosave), dirty columns are flushed to the
 * backing {@link RegionStore}.</p>
 *
 * <p><strong>Not thread-safe; intended for single-threaded server use.</strong>
 * No internal locking is provided in v1.</p>
 */
public final class SectionStore {

    private final RegionStore region;
    private final AmbientProvider ambient;

    /**
     * Loaded columns keyed by {@link #colKey(int, int)}. Each value is a
     * {@link NavigableMap} of sectionY → {@link SectionData}.
     */
    private final Map<Long, NavigableMap<Integer, SectionData>> loaded = new HashMap<>();

    /** Keys of columns that have been written but not yet flushed to disk. */
    private final Set<Long> dirty = new HashSet<>();

    /**
     * @param region  backing persistent store; must not be {@code null}
     * @param ambient supplier of ambient T and mass for never-simulated sections; must not be {@code null}
     */
    public SectionStore(RegionStore region, AmbientProvider ambient) {
        this.region  = Objects.requireNonNull(region,  "region");
        this.ambient = Objects.requireNonNull(ambient, "ambient");
    }

    // -------------------------------------------------------------------------
    // Key packing
    // -------------------------------------------------------------------------

    private static long colKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // -------------------------------------------------------------------------
    // Column lifecycle
    // -------------------------------------------------------------------------

    /**
     * Loads a chunk column from disk into memory.
     *
     * <p>Idempotent — if the column is already in memory (whether clean or dirty), this
     * call is a no-op. The live in-memory state is never clobbered by a stale disk read.
     * Called by the chunk-load event handler.</p>
     *
     * <p>If the backing region file does not exist yet, {@link RegionStore#loadColumn}
     * returns an empty map without touching disk, so absent regions never create spurious
     * region files.</p>
     */
    public void loadColumn(int cx, int cz) {
        long key = colKey(cx, cz);
        if (loaded.containsKey(key)) return;          // idempotent — don't clobber live state
        loaded.put(key, region.loadColumn(cx, cz));   // RegionStore avoids creating files for absent regions
    }

    /**
     * Returns whether a chunk column is currently held in memory.
     *
     * @return {@code true} if the column is in the in-memory map
     */
    public boolean isLoaded(int cx, int cz) {
        return loaded.containsKey(colKey(cx, cz));
    }

    /**
     * Flushes a column to disk if dirty, then drops it from memory.
     *
     * <p>Clean columns (never written via {@link #put}) are silently dropped without
     * any disk I/O, so ambient-only access never creates spurious region files.
     * Called by the chunk-unload event handler.</p>
     */
    public void unloadColumn(int cx, int cz) {
        long key = colKey(cx, cz);
        if (dirty.contains(key)) {
            region.saveColumn(cx, cz, loaded.get(key));
        }
        loaded.remove(key);
        dirty.remove(key);
    }

    /**
     * Persists all dirty columns to disk without evicting them from memory.
     *
     * <p>Called on level-save (autosave). Columns remain loaded so the engine can
     * continue reading/writing them after the save.</p>
     */
    public void flushAll() {
        for (long key : dirty) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            region.saveColumn(cx, cz, loaded.get(key));
        }
        dirty.clear();
    }

    // -------------------------------------------------------------------------
    // Section access
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link SectionData} for the given key.
     *
     * <p>If the column is loaded AND contains the requested sectionY, that live
     * section is returned. Otherwise a fresh ambient {@code UNIFORM} section is
     * synthesized from the {@link AmbientProvider} and returned — it is <em>not</em>
     * inserted into storage, so unsimulated regions never create persistent state and
     * do not mark any column dirty.</p>
     */
    public SectionData get(SubchunkKey key) {
        long ck = colKey(key.cx(), key.cz());
        NavigableMap<Integer, SectionData> col = loaded.get(ck);
        if (col != null) {
            SectionData data = col.get(key.sectionY());
            if (data != null) {
                return data;
            }
        }
        // Materialize ambient on demand — never stored, never dirty
        return SectionData.uniform(
                ambient.ambientTemperatureK(key),
                ambient.ambientMassKg(key));
    }

    /**
     * Writes a section into the in-memory store and marks the column dirty.
     *
     * <p>If the column is not yet loaded, an empty in-memory column is created
     * without reading disk — the chunk-load path ({@link #loadColumn}) is the
     * only disk-read entry point.</p>
     *
     * @param key  section address
     * @param data section payload; must not be {@code null}
     */
    public void put(SubchunkKey key, SectionData data) {
        Objects.requireNonNull(data, "data");
        long ck = colKey(key.cx(), key.cz());
        NavigableMap<Integer, SectionData> col =
                loaded.computeIfAbsent(ck, k -> new TreeMap<>());
        col.put(key.sectionY(), data);
        dirty.add(ck);
    }
}
