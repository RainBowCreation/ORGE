package net.rainbowcreation.orge.section;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The separate compressed region store under {@code world/orge/} (DESIGN.md §5).
 *
 * <p>Sections are grouped into region files {@code r.<rx>.<rz>.orge}, loaded/unloaded
 * alongside the corresponding chunk. The vanilla {@code .mca} files are never touched.
 * Each section is written as {@link SectionData.Form#UNIFORM} or
 * {@link SectionData.Form#FULL} ({@code deflate} of the two float arrays).</p>
 *
 * <p>The persistence unit is a <em>chunk column</em> — all sections stacked in Y for one
 * chunk (cx, cz). Use {@link #loadColumn} / {@link #saveColumn} to read or write a column.</p>
 */
public final class RegionStore implements Closeable {

    /** Subdirectory of the world save that holds ORGE region files. */
    public static final String DIR_NAME = "orge";

    private final Path orgeDir;

    /** Open region files, keyed by {@code regionKey(cx, cz)} — one entry per region (32×32 chunks). */
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

    /** Returns the packed cache key for the region that contains chunk (cx, cz). */
    private static long regionKey(int cx, int cz) {
        int rx = cx >> 5, rz = cz >> 5;
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Returns the on-disk path for the region file that contains chunk (cx, cz). */
    private Path regionFilePath(int cx, int cz) {
        int rx = cx >> 5, rz = cz >> 5;
        return orgeDir.resolve("r." + rx + "." + rz + ".orge");
    }

    /**
     * Returns (opening lazily) the {@link RegionFile} that covers chunk column (cx, cz).
     * Throws {@link UncheckedIOException} if the file cannot be opened.
     */
    private RegionFile region(int cx, int cz) {
        long key = regionKey(cx, cz);
        return open.computeIfAbsent(key, k -> {
            try {
                Files.createDirectories(orgeDir);
                return new RegionFile(regionFilePath(cx, cz));
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
        // If the region file is not already open in the cache AND does not exist on disk,
        // return an empty map immediately — do not open/create the file.
        long key = regionKey(cx, cz);
        if (!open.containsKey(key) && !Files.exists(regionFilePath(cx, cz))) {
            return new TreeMap<>();
        }
        try {
            byte[] blob = region(cx, cz).readChunk(cx, cz);
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
        Objects.requireNonNull(sections, "sections");
        try {
            RegionFile rf = region(cx, cz);
            if (sections.isEmpty()) {
                rf.deleteChunk(cx, cz);
            } else {
                rf.writeChunk(cx, cz, SectionCodec.writeColumn(sections));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save column (" + cx + "," + cz + ")", e);
        }
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
                if (first == null) { first = e; } else { first.addSuppressed(e); }
            }
        }
        open.clear();
        if (first != null) throw new UncheckedIOException("failed to close region file(s)", first);
    }

    /**
     * Implements {@link Closeable}; delegates to {@link #closeAll()}.
     * Any failure closing a region file surfaces as {@link UncheckedIOException}.
     */
    @Override
    public void close() {
        closeAll();
    }
}
