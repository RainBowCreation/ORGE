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
    void badVersionThrowsIOException() {
        // ONLY v5 is valid now (law §7 schema change: raw T/v → extensive E/momentum; no migration).
        // v1, v3, v4 (older raw-T/v blobs) and 99 (garbage / future) ALL throw.
        for (byte bad : new byte[]{1, 3, 4, 99}) {
            byte[] blob = new byte[]{bad, 0, 0};
            IOException ex = assertThrows(IOException.class, () -> SectionCodec.readColumn(blob),
                    "version " + bad + " must throw IOException");
            assertTrue(ex.getMessage().contains("fresh world"),
                    "reject message must tell the user to start a fresh world, was: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Test 6b: v5 save→load conservation — E/momentum/mass/material/pressure exact
    // -------------------------------------------------------------------------

    @Test
    void v5RoundTripsEnthalpyMomentumPressureExactly() throws IOException {
        float[] e = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            e[i] = 1000f + i * 0.5f;                 // distinct per-cell enthalpy E [J]
            m[i] = (i % 3 == 0) ? 1000f : 250f + i;  // distinct per-cell mass
        }
        SectionData s = SectionData.full(e, m);
        for (int i = 0; i < SectionData.CELLS; i++) {
            s.setMomentum(i, i * 0.25f, -i * 0.5f, i * 0.75f); // distinct momentum px,py,pz
            s.setPressure(i, i * 1.5f);                          // distinct pressure
        }
        net.minecraft.resources.Identifier stone =
                net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "stone");
        net.minecraft.resources.Identifier water =
                net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "water");
        s.setMaterialAt(0, stone);
        s.setMaterialAt(1, water);
        s.setMaterialAt(4095, stone);

        Map<Integer, SectionData> column = new TreeMap<>();
        column.put(7, s);
        byte[] blob = SectionCodec.writeColumn(column);
        assertEquals(5, blob[0], "blob must carry the v5 version byte");

        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(7);
        for (int i = 0; i < SectionData.CELLS; i++) {
            assertEquals(e[i], r.enthalpyAt(i), "E[" + i + "] bit-identical");
            assertEquals(m[i], r.massAt(i), "mass[" + i + "] bit-identical");
            assertEquals(i * 0.25f, r.momXAt(i), "px[" + i + "] bit-identical");
            assertEquals(-i * 0.5f, r.momYAt(i), "py[" + i + "] bit-identical");
            assertEquals(i * 0.75f, r.momZAt(i), "pz[" + i + "] bit-identical");
            assertEquals(i * 1.5f, r.pAt(i), "p[" + i + "] bit-identical");
        }
        assertEquals(stone, r.materialAt(0), "material[0] bit-identical");
        assertEquals(water, r.materialAt(1), "material[1] bit-identical");
        assertEquals(stone, r.materialAt(4095), "material[4095] bit-identical");
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
