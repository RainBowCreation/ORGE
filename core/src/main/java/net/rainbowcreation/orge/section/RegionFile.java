package net.rainbowcreation.orge.section;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * Vanilla-style 4 KiB-sector file container for ORGE column blobs (DESIGN.md §5, Task 3).
 *
 * <p>Stores up to 1024 opaque byte[] blobs, each addressed by a local chunk coordinate
 * {@code (lx, lz)} where {@code lx, lz ∈ [0, 31]}. The file format is identical in structure
 * to Minecraft's .mca RegionFile, but stores ORGE column blobs instead of chunk NBT.</p>
 *
 * <h3>File format (all values big-endian)</h3>
 * <pre>
 * Sector 0 (bytes 0..4095): location table, 1024 ints
 *   slot index = lx + lz * 32  (0..1023)
 *   entry int  = (sectorOffset &lt;&lt; 8) | sectorCount
 *   sectorOffset == 0  →  slot is empty
 *
 * Sectors ≥1: payloads, 4 KiB-aligned
 *   int  blobLength        ; does NOT include this 4-byte prefix
 *   byte[blobLength]       ; the opaque column blob
 *   (zero-padded to next 4096-byte boundary)
 * </pre>
 */
public final class RegionFile implements Closeable {

    /** Size of each sector in bytes. */
    public static final int SECTOR_BYTES = 4096;

    /** Number of slots (32×32). */
    public static final int SLOTS = 1024;

    private final RandomAccessFile raf;

    /** In-memory mirror of the 1024-int location table (sector 0). */
    private final int[] locations = new int[SLOTS];

    /** Which sectors are in use; bit 0 = sector 0 (header). */
    private final BitSet usedSectors = new BitSet();

    /** Total number of sectors currently in the file (file length / SECTOR_BYTES). */
    private int totalSectors;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Opens (or creates) the region file at {@code file}.
     *
     * <p>If the file does not exist or is shorter than one sector, a zero header sector is written.
     * Any partial trailing sector is padded up to a full sector boundary.</p>
     *
     * @param file path to the .orge region file
     * @throws IOException on I/O errors
     */
    public RegionFile(Path file) throws IOException {
        raf = new RandomAccessFile(file.toFile(), "rw");

        // Ensure file is at least one sector (zero header).
        if (raf.length() < SECTOR_BYTES) {
            raf.seek(0);
            raf.write(new byte[SECTOR_BYTES]);
        }

        // Pad to a multiple of SECTOR_BYTES if needed.
        long len = raf.length();
        if (len % SECTOR_BYTES != 0) {
            raf.setLength(len + (SECTOR_BYTES - (len % SECTOR_BYTES)));
        }

        totalSectors = (int) (raf.length() / SECTOR_BYTES);

        // Read location table.
        raf.seek(0);
        for (int i = 0; i < SLOTS; i++) {
            locations[i] = raf.readInt();
        }

        // Build usedSectors BitSet from location table.
        usedSectors.set(0); // sector 0 is the header
        for (int i = 0; i < SLOTS; i++) {
            int entry = locations[i];
            int offset = entry >>> 8;
            int count  = entry & 0xFF;
            if (offset != 0 && count != 0) {
                // NOTE: overlapping/out-of-range runs in a corrupt header are not validated here (v1; matches vanilla .mca leniency).
                usedSectors.set(offset, offset + count);
            }
        }
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
        int s     = slot(lx, lz);
        int entry  = locations[s];
        int offset = entry >>> 8;
        int count  = entry & 0xFF;
        if (offset == 0) {
            return null;
        }

        raf.seek(offset * (long) SECTOR_BYTES);
        int len = raf.readInt();
        int maxBlobBytes = count * SECTOR_BYTES - 4;
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
     * <p>If the new blob fits in the same number of sectors as the existing entry, the data
     * is written in-place. Otherwise the old sectors are freed and new sectors are allocated.</p>
     *
     * @throws IOException if the blob is too large (requires &gt;255 sectors), or on I/O errors
     */
    public void write(int lx, int lz, byte[] blob) throws IOException {
        int s = slot(lx, lz);

        int needBytes   = 4 + blob.length;
        int needSectors = (needBytes + SECTOR_BYTES - 1) / SECTOR_BYTES; // ceil-div

        if (needSectors > 255) {
            throw new IOException("column too large: " + needSectors + " sectors exceeds 255-sector slot limit");
        }

        int entry     = locations[s];
        int oldOffset = entry >>> 8;
        int oldCount  = entry & 0xFF;

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
        raf.write(blob);

        int written    = needBytes;
        int totalBytes = needSectors * SECTOR_BYTES;
        int padding    = totalBytes - written;
        if (padding > 0) {
            raf.write(new byte[padding]);
        }

        // Update in-memory state and persist header entry.
        usedSectors.set(offset, offset + needSectors);
        locations[s] = (offset << 8) | needSectors;
        raf.seek(s * 4L);
        raf.writeInt(locations[s]);
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
        int s     = slot(lx, lz);
        int entry  = locations[s];
        int offset = entry >>> 8;
        int count  = entry & 0xFF;

        if (offset != 0) {
            usedSectors.clear(offset, offset + count);
        }

        locations[s] = 0;
        raf.seek(s * 4L);
        raf.writeInt(0);
    }

    // -------------------------------------------------------------------------
    // Sector allocation
    // -------------------------------------------------------------------------

    /**
     * Allocates a run of {@code count} consecutive free sectors (sector 0 is always reserved).
     *
     * <p>Uses first-fit: scans from sector 1. If no fitting run exists within the current file,
     * the file is extended.</p>
     *
     * @return the starting sector index of the allocated run
     * @throws IOException if the file must be extended and the extension fails
     */
    private int allocate(int count) throws IOException {
        // First-fit scan starting from sector 1.
        int candidate = 1;
        while (candidate + count <= totalSectors) {
            // Check if [candidate, candidate+count) is all clear.
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
