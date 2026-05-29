package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link SectionCodec} — binary (de)serialization of sections and columns.
 */
class SectionCodecTest {

    // -------------------------------------------------------------------------
    // Helper: round-trip a single SectionData through write/read
    // -------------------------------------------------------------------------

    private static SectionData roundTrip(SectionData s) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            SectionCodec.writeSection(dos, s);
        }
        byte[] bytes = bos.toByteArray();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return SectionCodec.readSection(dis);
        }
    }

    // -------------------------------------------------------------------------
    // Test 1: UNIFORM round-trip
    // -------------------------------------------------------------------------

    @Test
    void uniformRoundTrip() throws IOException {
        SectionData original = SectionData.uniform(287.5f, 1000f);
        SectionData result = roundTrip(original);

        assertTrue(original.equalsValue(result), "Value must match after round-trip");
        assertEquals(SectionData.Form.UNIFORM, result.form(), "Form must remain UNIFORM");
    }

    // -------------------------------------------------------------------------
    // Test 2: FULL round-trip
    // -------------------------------------------------------------------------

    @Test
    void fullRoundTrip() throws IOException {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            t[i] = 270f + i * 0.01f;
            m[i] = (i % 2 == 0) ? 1000f : 0f;
        }
        SectionData original = SectionData.full(t, m);
        SectionData result = roundTrip(original);

        assertTrue(original.equalsValue(result), "Value must match after round-trip");
        assertEquals(SectionData.Form.FULL, result.form(), "Form must remain FULL");
    }

    // -------------------------------------------------------------------------
    // Test 3: FULL uses compression (byte length < raw arrays size)
    // -------------------------------------------------------------------------

    @Test
    void fullUsesCompression() throws IOException {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            t[i] = 270f + i * 0.01f;
            m[i] = (i % 2 == 0) ? 1000f : 0f;
        }
        SectionData section = SectionData.full(t, m);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            SectionCodec.writeSection(dos, section);
        }
        int serializedLen = bos.toByteArray().length;

        // Two raw arrays alone would be 2 * 4096 * 4 = 32768 bytes; deflate must shrink it
        assertTrue(serializedLen < 32768,
                "Serialized FULL section (" + serializedLen + " bytes) must be less than 32768 bytes (raw arrays size)");
    }

    // -------------------------------------------------------------------------
    // Test 4: Column round-trip
    // -------------------------------------------------------------------------

    @Test
    void columnRoundTrip() throws IOException {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            t[i] = 270f + i * 0.01f;
            m[i] = (i % 2 == 0) ? 1000f : 0f;
        }
        SectionData fullSection = SectionData.full(t, m);

        Map<Integer, SectionData> column = new TreeMap<>();
        column.put(-4, SectionData.uniform(285f, 1.2f));
        column.put(0, fullSection);
        column.put(19, SectionData.uniform(290f, 1000f));

        byte[] blob = SectionCodec.writeColumn(column);
        NavigableMap<Integer, SectionData> result = SectionCodec.readColumn(blob);

        assertEquals(column.keySet(), result.keySet(), "KeySet must match");
        assertTrue(column.get(-4).equalsValue(result.get(-4)), "Section at -4 must match");
        assertTrue(column.get(0).equalsValue(result.get(0)), "Section at 0 must match");
        assertTrue(column.get(19).equalsValue(result.get(19)), "Section at 19 must match");
        assertEquals(SectionData.Form.FULL, result.get(0).form(), "Section at 0 must be FULL");
    }

    // -------------------------------------------------------------------------
    // Test 5: Empty column
    // -------------------------------------------------------------------------

    @Test
    void emptyColumnRoundTrip() throws IOException {
        byte[] blob = SectionCodec.writeColumn(new TreeMap<>());
        NavigableMap<Integer, SectionData> result = SectionCodec.readColumn(blob);

        assertTrue(result.isEmpty(), "Read-back of empty column must be empty");
    }

    // -------------------------------------------------------------------------
    // Test 6: Bad version throws IOException
    // -------------------------------------------------------------------------

    @Test
    void badVersionThrowsIOException() throws IOException {
        // Craft a blob with version=2 (unsupported)
        byte[] blob = new byte[]{2, 0, 0};
        assertThrows(IOException.class, () -> SectionCodec.readColumn(blob),
                "Unsupported version must throw IOException");
    }

    // -------------------------------------------------------------------------
    // Test 7: Unknown section form throws IOException
    // -------------------------------------------------------------------------

    @Test
    void unknownSectionFormThrowsIOException() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeByte(0x07); // unknown form byte
        }
        byte[] bytes = bos.toByteArray();
        assertThrows(IOException.class, () -> {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
                SectionCodec.readSection(dis);
            }
        }, "Unknown section form must throw IOException");
    }
}
