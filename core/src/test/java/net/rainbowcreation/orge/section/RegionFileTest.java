package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link RegionFile} — 4 KiB-sector column blob container (DESIGN.md §5, Task 3).
 */
class RegionFileTest {

    @TempDir
    Path dir;

    /** Fills a byte[] of length {@code len} with a deterministic pattern based on {@code seed}. */
    private static byte[] blob(int len, int seed) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) ((seed * 31 + i * 7) & 0xFF);
        }
        return b;
    }

    // -------------------------------------------------------------------------
    // Test 1: Create + round-trip across reopen
    // -------------------------------------------------------------------------

    @Test
    void createAndRoundTripAcrossReopen() throws IOException {
        Path file = dir.resolve("r.0.0.orge");
        byte[] A = blob(200, 1);
        byte[] B = blob(8500, 2); // spans multiple sectors

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(1, 2, A);
            rf.write(5, 5, B);
        }

        try (RegionFile rf = new RegionFile(file)) {
            assertArrayEquals(A, rf.read(1, 2), "slot (1,2) should read back blob A");
            assertArrayEquals(B, rf.read(5, 5), "slot (5,5) should read back blob B");
            assertNull(rf.read(0, 0), "slot (0,0) was never written — should be null");
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: Overwrite smaller→larger forces reallocation without corrupting neighbors
    // -------------------------------------------------------------------------

    @Test
    void overwriteSmallerToLargerReallocatesWithoutCorruptingNeighbors() throws IOException {
        Path file = dir.resolve("r.1.0.orge");
        byte[] small = blob(100, 10);
        byte[] other = blob(200, 11); // neighbor
        byte[] large = blob(9000, 12); // 3 sectors

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(3, 3, small);
            rf.write(4, 4, other);
            rf.write(3, 3, large); // overwrite with larger blob
        }

        try (RegionFile rf = new RegionFile(file)) {
            assertArrayEquals(large, rf.read(3, 3), "slot (3,3) should have the large blob after overwrite");
            assertArrayEquals(other, rf.read(4, 4), "slot (4,4) neighbor should be intact");
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: Reuse same size in place
    // -------------------------------------------------------------------------

    @Test
    void reuseSameSizeInPlace() throws IOException {
        Path file = dir.resolve("r.2.0.orge");
        byte[] x = blob(5000, 20); // 2 sectors (5000+4 = 5004 bytes)
        byte[] y = blob(5000, 21); // also 2 sectors, different content

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(6, 6, x);
            rf.write(6, 6, y); // same size, should reuse in-place
        }

        try (RegionFile rf = new RegionFile(file)) {
            assertArrayEquals(y, rf.read(6, 6), "slot (6,6) should have the updated blob y");
        }
    }

    // -------------------------------------------------------------------------
    // Test 4: Delete then reuse
    // -------------------------------------------------------------------------

    @Test
    void deleteThenReuse() throws IOException {
        Path file = dir.resolve("r.3.0.orge");
        byte[] blobA = blob(300, 30);
        byte[] blobB = blob(400, 31);

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(7, 7, blobA);
            rf.delete(7, 7);
            assertNull(rf.read(7, 7), "after delete, read should return null");
            rf.write(7, 7, blobB);
        }

        try (RegionFile rf = new RegionFile(file)) {
            assertArrayEquals(blobB, rf.read(7, 7), "slot (7,7) should have blobB after re-write");
        }
    }

    // -------------------------------------------------------------------------
    // Test 5: Boundary slots independent
    // -------------------------------------------------------------------------

    @Test
    void boundarySlotsIndependent() throws IOException {
        Path file = dir.resolve("r.4.0.orge");
        byte[] b00   = blob(50, 40);   // slot 0
        byte[] b3131 = blob(60, 41);   // slot 1023
        byte[] b310  = blob(70, 42);   // slot 31
        byte[] b031  = blob(80, 43);   // slot 992

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(0, 0, b00);
            rf.write(31, 31, b3131);
            rf.write(31, 0, b310);
            rf.write(0, 31, b031);
        }

        try (RegionFile rf = new RegionFile(file)) {
            assertArrayEquals(b00,   rf.read(0, 0),   "slot (0,0)   → index 0");
            assertArrayEquals(b3131, rf.read(31, 31), "slot (31,31) → index 1023");
            assertArrayEquals(b310,  rf.read(31, 0),  "slot (31,0)  → index 31");
            assertArrayEquals(b031,  rf.read(0, 31),  "slot (0,31)  → index 992");
        }
    }

    // -------------------------------------------------------------------------
    // Test 6: New/empty file
    // -------------------------------------------------------------------------

    @Test
    void newEmptyFile() throws IOException {
        Path file = dir.resolve("r.5.0.orge");
        assertFalse(java.nio.file.Files.exists(file), "file should not exist yet");

        try (RegionFile rf = new RegionFile(file)) {
            assertTrue(java.nio.file.Files.exists(file), "constructor should create the file");
            assertTrue(java.nio.file.Files.size(file) >= 4096, "new file should be at least 4096 bytes");
            assertNull(rf.read(0, 0), "all slots should be null in a new file");
            assertNull(rf.read(15, 15), "all slots should be null in a new file");
            assertNull(rf.read(31, 31), "all slots should be null in a new file");
        }
    }

    // -------------------------------------------------------------------------
    // Test 7: Out-of-range local coords throw IllegalArgumentException
    // -------------------------------------------------------------------------

    @Test
    void outOfRangeCoordsThrow() throws IOException {
        Path file = dir.resolve("r.6.0.orge");
        byte[] data = blob(100, 50);

        try (RegionFile rf = new RegionFile(file)) {
            assertThrows(IllegalArgumentException.class, () -> rf.read(32, 0),
                    "lx=32 should throw IllegalArgumentException");
            assertThrows(IllegalArgumentException.class, () -> rf.write(0, 32, data),
                    "lz=32 should throw IllegalArgumentException");
            assertThrows(IllegalArgumentException.class, () -> rf.read(-1, 0),
                    "lx=-1 should throw IllegalArgumentException");
            assertThrows(IllegalArgumentException.class, () -> rf.delete(0, -1),
                    "lz=-1 should throw IllegalArgumentException");
        }
    }

    // -------------------------------------------------------------------------
    // Test 8: Empty blob round-trips (distinguishes "present but empty" from "absent")
    // -------------------------------------------------------------------------

    @Test
    void emptyBlobRoundTrips() throws IOException {
        Path file = dir.resolve("r.7.0.orge");

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(2, 2, new byte[0]);
        }

        try (RegionFile rf = new RegionFile(file)) {
            byte[] result = rf.read(2, 2);
            assertNotNull(result, "empty blob should read back as non-null (present but empty)");
            assertEquals(0, result.length, "empty blob should read back with length 0");
        }
    }

    // -------------------------------------------------------------------------
    // Test 9: Blob far larger than the old 255-sector cap round-trips bit-identical
    // (proves the save-crash that hit at ~388 sectors is lifted)
    // -------------------------------------------------------------------------

    @Test
    void largeBlobOver255SectorsRoundTripsBitIdentical() throws IOException {
        Path file = dir.resolve("r.8.0.orge");
        byte[] big = blob(2_000_000, 99); // ~489 sectors, > old 255 cap AND > the 388 that crashed

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(10, 10, big);
            assertArrayEquals(big, rf.read(10, 10), "large blob must round-trip bit-identical");
        }
    }

    // -------------------------------------------------------------------------
    // Test 10: Blob at EXACTLY 256 sectors (the old 255 boundary) round-trips
    // (pins the 255/256 off-by-one)
    // -------------------------------------------------------------------------

    @Test
    void blobAtExactly256SectorsRoundTrips() throws IOException {
        Path file = dir.resolve("r.9.0.orge");
        // needSectors == 256 exactly: 4 (length prefix) + len == 256*4096.
        int len = 256 * 4096 - 4;
        byte[] data = blob(len, 7);

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(11, 11, data);
            assertArrayEquals(data, rf.read(11, 11), "256-sector blob must round-trip bit-identical");
        }
    }

    // -------------------------------------------------------------------------
    // Test 11: Two large multi-sector slots — free/realloc must not overlap neighbor
    // -------------------------------------------------------------------------

    @Test
    void twoLargeSlotsWrittenFreedRewrittenWithoutCorruption() throws IOException {
        Path file = dir.resolve("r.10.0.orge");
        byte[] big1 = blob(1_500_000, 1); // ~367 sectors
        byte[] big2 = blob(1_800_000, 2); // ~440 sectors

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(0, 0, big1);
            rf.write(1, 1, big2);
            assertArrayEquals(big1, rf.read(0, 0), "slot (0,0) should read back big1");
            assertArrayEquals(big2, rf.read(1, 1), "slot (1,1) should read back big2");

            byte[] big3 = blob(2_100_000, 3); // larger than big1's freed run
            rf.delete(0, 0);
            rf.write(0, 0, big3);

            assertArrayEquals(big3, rf.read(0, 0), "slot (0,0) should read back the rewritten big3");
            assertArrayEquals(big2, rf.read(1, 1), "slot (1,1) neighbor must be untouched by the realloc");
        }
    }

    // -------------------------------------------------------------------------
    // Test 12: Reopen rebuilds the free map from disk; a new alloc must not
    // overlap the runs reconstructed from the persisted location table
    // -------------------------------------------------------------------------

    @Test
    void reopenAfterCloseRebuildsFreeMapAndReadsBothLargeSlots() throws IOException {
        Path file = dir.resolve("r.11.0.orge");
        byte[] big1 = blob(1_500_000, 1); // ~367 sectors
        byte[] big2 = blob(1_800_000, 2); // ~440 sectors

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(2, 2, big1);
            rf.write(3, 3, big2);
        }

        try (RegionFile rf = new RegionFile(file)) {
            assertArrayEquals(big1, rf.read(2, 2), "slot (2,2) should read back big1 after reopen");
            assertArrayEquals(big2, rf.read(3, 3), "slot (3,3) should read back big2 after reopen");

            byte[] big3 = blob(900_000, 4); // new slot allocated against the rebuilt free map
            rf.write(4, 4, big3);

            assertArrayEquals(big1, rf.read(2, 2), "slot (2,2) must survive the new alloc (no overlap)");
            assertArrayEquals(big2, rf.read(3, 3), "slot (3,3) must survive the new alloc (no overlap)");
            assertArrayEquals(big3, rf.read(4, 4), "slot (4,4) should read back the newly written big3");
        }
    }

    // -------------------------------------------------------------------------
    // Test 13: An OLD pre-ORGB-format .orge file is rejected with a clear message
    // (no migration — tell the user to start a fresh world)
    // -------------------------------------------------------------------------

    @Test
    void preMagicFileRejectedWithClearMessage() throws IOException {
        Path file = dir.resolve("old.0.0.orge");
        // Write one 4096-byte sector whose first int is an old-style location entry
        // (sectorOffset 2, count 1 → (2<<8)|1), so byte0..3 != MAGIC.
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(4096);
            raf.seek(0);
            raf.writeInt((2 << 8) | 1);
        }

        IOException ex = assertThrows(IOException.class, () -> new RegionFile(file),
                "an old pre-ORGB .orge file must be rejected");
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
        assertTrue(msg.contains("magic") || msg.contains("fresh world"),
                "reject message must mention 'magic' or 'fresh world': " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Test 14: A correctly-magicked file with a WRONG version is rejected
    // -------------------------------------------------------------------------

    @Test
    void unsupportedVersionRejected() throws IOException {
        Path file = dir.resolve("ver.0.0.orge");
        // Sector 0: MAGIC @0, wrong version 999 @4; sector 1 zeroed location table.
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(2L * 4096);
            raf.seek(0);
            raf.writeInt(RegionFile.MAGIC);
            raf.writeInt(999);
        }

        IOException ex = assertThrows(IOException.class, () -> new RegionFile(file),
                "a file with the wrong ORGE version must be rejected");
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
        assertTrue(msg.contains("version"),
                "reject message must mention 'version': " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Test 15: A fresh file is created WITH the magic+version and round-trips on reopen
    // (proves the reject does not break the create+reopen cycle)
    // -------------------------------------------------------------------------

    @Test
    void freshFileCreatedWithMagicAndRoundTrips() throws IOException {
        Path file = dir.resolve("fresh.0.0.orge");
        byte[] data = blob(1000, 1);

        try (RegionFile rf = new RegionFile(file)) {
            rf.write(5, 5, data);
        }

        // Reopen must NOT throw — the magic+version were written by the fresh-create branch.
        try (RegionFile rf = new RegionFile(file)) {
            assertArrayEquals(data, rf.read(5, 5),
                    "fresh file must round-trip after reopen (magic was persisted)");
        }
    }

    // -------------------------------------------------------------------------
    // Test 16: A file with VALID magic+version but truncated header (< 2 sectors)
    // is rejected with a clear ORGE message — NOT a bare EOFException
    // -------------------------------------------------------------------------

    @Test
    void truncatedValidHeaderRejected() throws IOException {
        Path file = dir.resolve("trunc.0.0.orge");
        // 8 bytes: MAGIC @0 + VERSION @4, then EOF — a valid header start but far short of the
        // 2-sector header region. Must reject with a clear message, not a raw EOFException.
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(8L);
            raf.seek(0);
            raf.writeInt(RegionFile.MAGIC);
            raf.writeInt(RegionFile.VERSION);
        }

        IOException ex = assertThrows(IOException.class, () -> new RegionFile(file),
                "a valid-magic but truncated (< 2 sector) file must be rejected");
        assertFalse(ex instanceof java.io.EOFException,
                "reject must be a clear ORGE IOException, not a bare EOFException: " + ex);
        assertNotNull(ex.getMessage(), "reject message must be non-null (not a bare EOFException)");
        String msg = ex.getMessage().toLowerCase(java.util.Locale.ROOT);
        assertTrue(msg.contains("region") || msg.contains("fresh world"),
                "reject message must mention 'region' or 'fresh world': " + ex.getMessage());
    }
}
