package net.rainbowcreation.orge.section;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The separate compressed region store under {@code world/orge/} (DESIGN.md §5).
 *
 * <p>Sections are grouped into region files {@code r.<rx>.<rz>.orge}, loaded/unloaded
 * alongside the corresponding chunk. The vanilla {@code .mca} files are never touched.
 * Each section is written as {@link SectionData.Form#UNIFORM} or
 * {@link SectionData.Form#FULL} ({@code deflate} of the two float arrays).</p>
 *
 * <p>The primary persistence unit is a <em>chunk column</em> — all sections stacked
 * in Y for one chunk (cx, cz). Use {@link #loadColumn} / {@link #saveColumn} when
 * operating on multiple sections at once. The per-section convenience methods
 * {@link #load(SubchunkKey)} and {@link #save(SubchunkKey, SectionData)} are backed
 * by a read-modify-write on the full column and are therefore more expensive when
 * called repeatedly for the same chunk.</p>
 */
public final class RegionStore implements Closeable {

    /** Subdirectory of the world save that holds ORGE region files. */
    public static final String DIR_NAME = "orge";

    private final Path orgeDir;

    /** Open region files, keyed by {@code regionKey(rx, rz)}. */
    private final Map<Long, RegionFile> open = new HashMap<>();

    public RegionStore(Path worldDir) {
        this.orgeDir = worldDir.resolve(DIR_NAME);
    }

    public Path directory() {
        return orgeDir;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns (opening lazily) the {@link RegionFile} that covers chunk column (cx, cz).
     * Throws {@link UncheckedIOException} if the file cannot be opened.
     */
    private RegionFile region(int cx, int cz) {
        int rx = cx >> 5, rz = cz >> 5;
        long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
        return open.computeIfAbsent(key, k -> {
            try {
                Files.createDirectories(orgeDir);
                return new RegionFile(orgeDir.resolve("r." + rx + "." + rz + ".orge"));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "failed to open region file for chunk (" + cx + "," + cz + ")", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Column API (primary interface)
    // -------------------------------------------------------------------------

    /**
     * Loads all sections for chunk column (cx, cz) from disk.
     *
     * @return a {@link NavigableMap} of sectionY → {@link SectionData}; never {@code null}.
     *         Returns an empty map when the column has not been written yet.
     * @throws UncheckedIOException if an I/O error occurs
     */
    public NavigableMap<Integer, SectionData> loadColumn(int cx, int cz) {
        try {
            byte[] blob = region(cx, cz).read(cx & 31, cz & 31);
            return blob == null ? new TreeMap<>() : SectionCodec.readColumn(blob);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load column (" + cx + "," + cz + ")", e);
        }
    }

    /**
     * Saves all sections for chunk column (cx, cz) to disk.
     * Passing an empty {@code sections} map deletes the column slot.
     *
     * @param sections sectionY → {@link SectionData}; empty map removes the slot
     * @throws UncheckedIOException if an I/O error occurs
     */
    public void saveColumn(int cx, int cz, Map<Integer, SectionData> sections) {
        try {
            RegionFile rf = region(cx, cz);
            if (sections.isEmpty()) {
                rf.delete(cx & 31, cz & 31);
            } else {
                rf.write(cx & 31, cz & 31, SectionCodec.writeColumn(sections));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save column (" + cx + "," + cz + ")", e);
        }
    }

    // -------------------------------------------------------------------------
    // Per-section convenience (column-backed read-modify-write)
    // -------------------------------------------------------------------------

    /**
     * Loads a single section by key.
     *
     * <p><strong>Note:</strong> the column is the primary persistence unit. This method
     * reads the full column blob from the region file and returns the requested section.
     * Prefer {@link #loadColumn} when reading multiple sections from the same chunk.</p>
     *
     * @return the section, or {@code null} if that sectionY is not present in the column
     * @throws UncheckedIOException if an I/O error occurs
     */
    public SectionData load(SubchunkKey key) {
        return loadColumn(key.cx(), key.cz()).get(key.sectionY());
    }

    /**
     * Saves a single section by key via a read-modify-write on the full column.
     *
     * <p><strong>Note:</strong> the column is the primary persistence unit. Each call
     * reads the column, updates the target section, and rewrites the entire column blob.
     * Prefer {@link #saveColumn} when writing multiple sections into the same chunk at once.</p>
     *
     * @throws UncheckedIOException if an I/O error occurs
     */
    public void save(SubchunkKey key, SectionData data) {
        NavigableMap<Integer, SectionData> col = loadColumn(key.cx(), key.cz());
        col.put(key.sectionY(), data);
        saveColumn(key.cx(), key.cz(), col);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Closes all open region files, suppressing individual close errors (first error
     * is re-thrown after all files have been attempted).
     *
     * @throws UncheckedIOException if any file failed to close (wraps the first error)
     */
    public void closeAll() {
        IOException first = null;
        for (RegionFile rf : open.values()) {
            try {
                rf.close();
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        open.clear();
        if (first != null) throw new UncheckedIOException("failed to close region file(s)", first);
    }

    /**
     * Implements {@link Closeable}; delegates to {@link #closeAll()}.
     */
    @Override
    public void close() {
        closeAll();
    }
}
