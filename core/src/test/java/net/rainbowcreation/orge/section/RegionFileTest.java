package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

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
}
