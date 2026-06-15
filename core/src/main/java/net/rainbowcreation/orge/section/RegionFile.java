package net.rainbowcreation.orge.section;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * Sector-based file container for ORGE column blobs (DESIGN.md §5, Task 3).
 *
 * <p>Stores up to 1024 opaque byte[] blobs, each addressed by a local chunk coordinate
 * {@code (lx, lz)} where {@code lx, lz ∈ [0, 31]}. Structurally similar to Minecraft's .mca
 * RegionFile, but stores ORGE column blobs instead of chunk NBT — and, crucially, has NO
 * per-slot sector-count ceiling: a single column may span an arbitrary number of sectors.</p>
 *
 * <h3>File format (all values big-endian)</h3>
 * <pre>
 * Sector 0 (bytes 0..4095): header
 *   int  MAGIC   @ byte 0  = 0x4F524742 ("ORGB")
 *   int  VERSION @ byte 4  = 2
 *   (rest zero-reserved)
 *
 * Sector 1 (bytes 4096..8191): location table, 1024 ints
 *   slot index = lx + lz * 32  (0..1023)
 *   entry int  = sectorOffset  (FULL 32-bit; NO shift/mask — the count is NOT stored)
 *   entry == 0  →  slot is empty
 *
 * Sectors ≥2: payloads, 4 KiB-aligned
 *   int  blobLength        ; does NOT include this 4-byte prefix
 *   byte[blobLength]       ; the opaque column blob
 *   (zero-padded to next 4096-byte boundary)
 *
 *   sectorCount is DERIVED:  ceil((4 + blobLength) / 4096)  — never stored.
 * </pre>
 */
public final class RegionFile implements Closeable {

    /** Size of each sector in bytes. */
    public static final int SECTOR_BYTES = 4096;

    /** Number of slots (32×32). */
    public static final int SLOTS = 1024;

    /** Header magic, "ORGB". */
    public static final int MAGIC = 0x4F524742;

    /** Current on-disk format version. */
    public static final int VERSION = 2;

    /** First sector available for payloads (0 = header, 1 = location table). */
    private static final int FIRST_PAYLOAD_SECTOR = 2;

    private final RandomAccessFile raf;

    /** In-memory mirror of the 1024-int location table (sector 1): per slot, the sector offset (0 = empty). */
    private final int[] locations = new int[SLOTS];

    /** In-memory mirror of each slot's DERIVED sector count (0 = empty). Avoids re-reading the prefix. */
    private final int[] counts = new int[SLOTS];

    /** Which sectors are in use; bits 0 and 1 = header + location table. */
    private final BitSet usedSectors = new BitSet();

    /** Total number of sectors currently in the file (file length / SECTOR_BYTES). */
    private int totalSectors;

    /** The magic int read from sector 0 of an existing file (SUBTASK 4 enforces rejection on mismatch). */
    private final int fileMagic;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Opens (or creates) the region file at {@code file}.
     *
     * <p>A file is CREATED (ORGB header sector + a zeroed location-table sector) ONLY when it is
     * truly empty (length == 0). An existing (non-empty) file's ORGB magic and version are
     * VALIDATED; a file with missing/wrong magic, an unsupported version, or a truncated header
     * (valid magic but shorter than the full 2-sector header region) is REJECTED with an
     * {@link IOException} — there is NO migration, the user is told to start a fresh world.
     * Once a file is accepted, the location table and per-slot derived counts are loaded and the
     * free-map is rebuilt from the payload prefixes.</p>
     *
     * @param file path to the .orge region file
     * @throws IOException on I/O errors, or if an existing file is not an ORGE region file
     *                     (missing/!=ORGB magic), is an unsupported version, or is truncated
     *                     (corrupt header) — in every reject case: delete world/orge and start
     *                     a fresh world
     */
    public RegionFile(Path file) throws IOException {
        raf = new RandomAccessFile(file.toFile(), "rw");

        if (raf.length() == 0L) {
            // FRESH (brand-new / zero-length) file: header sector 0 (magic + version) +
            // zeroed location-table sector 1. Only a TRULY EMPTY file is created — any existing
            // bytes (even a short old-format file < 2 sectors) must fall through to validation.
            raf.setLength(2L * SECTOR_BYTES);
            raf.seek(0);
            raf.writeInt(MAGIC);
            raf.writeInt(VERSION);
            // bytes 8..(2*SECTOR_BYTES-1) remain zero from setLength — header reserved + empty table.
            fileMagic = MAGIC;
        } else {
            // EXISTING, non-empty file. Validate the ORGB header BEFORE touching anything else:
            // NO migration of pre-format or wrong-version files — fail loud and tell the user to
            // start a fresh world. A file too short to even carry the 8-byte magic+version header
            // cannot be valid → reject with the missing-magic message.
            if (raf.length() < 8L) {
                raf.close();
                throw new IOException("not an ORGE region file (missing/!=ORGB magic): " + file
                        + " — pre-format .orge files are unsupported; delete world/orge and start a fresh world");
            }
            raf.seek(0);
            int magic = raf.readInt();
            if (magic != MAGIC) {
                raf.close();
                throw new IOException("not an ORGE region file (missing/!=ORGB magic): " + file
                        + " — pre-format .orge files are unsupported; delete world/orge and start a fresh world");
            }
            int version = raf.readInt();
            if (version != VERSION) {
                raf.close();
                throw new IOException("unsupported ORGE region version " + version
                        + ", expected " + VERSION + ": " + file + " — start a fresh world");
            }
            // Magic+version OK, but the file must still carry the FULL 2-sector header region
            // (sector 0 = header, sector 1 = location table). A valid-magic-but-truncated file
            // (< 2 sectors) is corrupt — reject it with the same clear missing-magic-style message
            // rather than letting the table read at sector 1 leak a bare EOFException. The writer
            // never produces this (fresh-create always lays 2 full sectors); this guards corruption.
            if (raf.length() < 2L * SECTOR_BYTES) {
                raf.close();
                throw new IOException("truncated/corrupt ORGE region file (header < 2 sectors): " + file
                        + " — delete world/orge and start a fresh world");
            }
            fileMagic = magic;

            // Header validated. Pad to a whole-sector boundary if a partial trailing sector exists.
            long len = raf.length();
            if (len % SECTOR_BYTES != 0) {
                raf.setLength(len + (SECTOR_BYTES - (len % SECTOR_BYTES)));
            }
        }

        totalSectors = (int) (raf.length() / SECTOR_BYTES);

        // Read location table (sector 1).
        raf.seek(SECTOR_BYTES);
        for (int i = 0; i < SLOTS; i++) {
            locations[i] = raf.readInt();
        }

        // Reserve header + location table.
        usedSectors.set(0);
        usedSectors.set(1);

        // Rebuild usedSectors + counts mirror by reading each non-empty slot's payload prefix.
        for (int i = 0; i < SLOTS; i++) {
            int offset = locations[i];
            if (offset >= FIRST_PAYLOAD_SECTOR && offset < totalSectors) {
                raf.seek(offset * (long) SECTOR_BYTES);
                int blobLength = raf.readInt();
                int count = sectorCountFor(blobLength);
                counts[i] = count;
                usedSectors.set(offset, Math.min(offset + count, totalSectors));
            } else {
                // Empty (0) or out-of-range — treat as empty (v1 leniency, matches vanilla .mca).
                locations[i] = 0;
                counts[i] = 0;
            }
        }
    }

    /** Derived sector count for a payload whose blob is {@code blobLength} bytes: ceil((4 + len)/4096). */
    private static int sectorCountFor(int blobLength) {
        return (4 + blobLength + SECTOR_BYTES - 1) / SECTOR_BYTES;
    }

    // -------------------------------------------------------------------------
    // Slot index
    // -------------------------------------------------------------------------

    /**
     * Computes the slot index for local chunk coordinates {@code (lx, lz)}.
     *
     * @throws IllegalArgumentException if either coordinate is outside [0, 31]
     */
    private int slot(int lx, int lz) {
        if (lx < 0 || lx > 31 || lz < 0 || lz > 31) {
            throw new IllegalArgumentException(
                    "local coord out of range: (" + lx + "," + lz + ")");
        }
        return lx + lz * 32;
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Reads the blob stored at local chunk coordinates {@code (lx, lz)}.
     *
     * @return the blob bytes, or {@code null} if the slot is empty
     * @throws IOException on I/O errors or corrupt data
     */
    public byte[] read(int lx, int lz) throws IOException {
        int s      = slot(lx, lz);
        int offset = locations[s];
        if (offset == 0) {
            return null;
        }

        raf.seek(offset * (long) SECTOR_BYTES);
        int len = raf.readInt();
        int maxBlobBytes = counts[s] * SECTOR_BYTES - 4;
        if (len < 0 || len > maxBlobBytes) {
            throw new IOException("corrupt region: blob length " + len +
                " out of range [0," + maxBlobBytes + "] at slot (" + lx + "," + lz + ")");
        }
        byte[] buf = new byte[len];
        raf.readFully(buf);
        return buf;
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Writes {@code blob} to local chunk coordinates {@code (lx, lz)}.
     *
     * <p>If the new blob occupies the same number of sectors as the existing entry, the data
     * is written in-place. Otherwise the old run is freed and a new run is allocated. There is
     * NO upper bound on the number of sectors a single blob may occupy.</p>
     *
     * @throws IOException on I/O errors
     */
    public void write(int lx, int lz, byte[] blob) throws IOException {
        int s = slot(lx, lz);

        int needSectors = sectorCountFor(blob.length);

        int oldOffset = locations[s];
        int oldCount  = counts[s];

        int offset;
        if (oldOffset != 0 && oldCount == needSectors) {
            // Reuse existing run in place.
            offset = oldOffset;
        } else {
            // Free old sectors (if any), then allocate fresh.
            if (oldOffset != 0) {
                usedSectors.clear(oldOffset, oldOffset + oldCount);
            }
            offset = allocate(needSectors);
        }

        // Write payload: 4-byte length prefix + blob + zero-padding to sector boundary.
        raf.seek(offset * (long) SECTOR_BYTES);
        raf.writeInt(blob.length);
        if (blob.length > 0) {
            raf.write(blob);
        }

        int written    = 4 + blob.length;
        int totalBytes  = needSectors * SECTOR_BYTES;
        int padding     = totalBytes - written;
        if (padding > 0) {
            raf.write(new byte[padding]);
        }

        // Update in-memory state and persist the header entry (offset only — count is derived).
        usedSectors.set(offset, offset + needSectors);
        locations[s] = offset;
        counts[s]    = needSectors;
        raf.seek(SECTOR_BYTES + s * 4L);
        raf.writeInt(offset);
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Deletes the blob at local chunk coordinates {@code (lx, lz)}.
     *
     * <p>If the slot was empty this is a no-op (except the header entry is persisted as 0).</p>
     *
     * @throws IOException on I/O errors
     */
    public void delete(int lx, int lz) throws IOException {
        int s      = slot(lx, lz);
        int offset = locations[s];

        if (offset != 0) {
            usedSectors.clear(offset, offset + counts[s]);
        }

        locations[s] = 0;
        counts[s]    = 0;
        raf.seek(SECTOR_BYTES + s * 4L);
        raf.writeInt(0);
    }

    // -------------------------------------------------------------------------
    // Sector allocation
    // -------------------------------------------------------------------------

    /**
     * Allocates a run of {@code count} consecutive free sectors (sectors 0 and 1 are reserved).
     *
     * <p>Uses first-fit: scans from the first payload sector. If no fitting run exists within the
     * current file, the file is extended.</p>
     *
     * @return the starting sector index of the allocated run
     * @throws IOException if the file must be extended and the extension fails
     */
    private int allocate(int count) throws IOException {
        // First-fit scan starting from the first payload sector.
        int candidate = FIRST_PAYLOAD_SECTOR;
        while (candidate + count <= totalSectors) {
            int nextUsed = usedSectors.nextSetBit(candidate);
            if (nextUsed == -1 || nextUsed >= candidate + count) {
                // Found a free run.
                usedSectors.set(candidate, candidate + count);
                return candidate;
            }
            // Jump past the used sector.
            candidate = nextUsed + 1;
        }

        // No fitting run within current file — append to end.
        int start = totalSectors;
        raf.setLength((start + (long) count) * SECTOR_BYTES);
        totalSectors += count;
        usedSectors.set(start, start + count);
        return start;
    }

    // -------------------------------------------------------------------------
    // Closeable
    // -------------------------------------------------------------------------

    /**
     * Closes the underlying {@link RandomAccessFile}.
     *
     * @throws IOException on I/O errors
     */
    @Override
    public void close() throws IOException {
        raf.close();
    }
}
