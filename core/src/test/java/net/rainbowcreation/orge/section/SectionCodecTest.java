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
        assertEquals(SectionCodec.FORMAT_VERSION, blob[0], "blob must carry the current version byte");

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
    // Test 6c: an OLD-format (v5, one-material) world migrates on load (issue #27)
    // -------------------------------------------------------------------------

    private static final net.minecraft.resources.Identifier WATER =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "water");
    private static final net.minecraft.resources.Identifier STONE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "stone");
    private static final net.minecraft.resources.Identifier AIR =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "air");
    private static final net.minecraft.resources.Identifier SAND =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("orge", "sand");

    /**
     * The column {@code fixtures/orge-column-v5.bin} holds, rebuilt in memory. That fixture was written
     * by the v5 (one-material) {@link SectionCodec} at base commit ad89802 from exactly this builder:
     * section −1 FULL (stone floor, sand, a water pool with momentum + pressure, air above; real
     * densities and E = m·cp·T), section 3 UNIFORM ambient air.
     */
    private static Map<Integer, SectionData> v5FixtureColumn() {
        Map<Integer, SectionData> col = new TreeMap<>();
        SectionData f = SectionData.uniform(0f, 0f);
        for (int i = 0; i < SectionData.CELLS; i++) {
            int sy = (i >> 4) & 15;
            net.minecraft.resources.Identifier id; float m; float cp;
            if (sy < 4)       { id = STONE; m = 2700f; cp = 790f; }
            else if (sy < 6)  { id = SAND;  m = 1600f; cp = 830f; }
            else if (sy < 11) { id = WATER; m = 1000f - (i % 7) * 0.125f; cp = 4186f; }
            else              { id = AIR;   m = 1.204f; cp = 1005f; }
            float t = 285f + (i % 13) * 0.5f;
            f.setMaterialAt(i, id);
            f.setMass(i, m);
            f.setEnthalpy(i, m * cp * t);
            if (sy >= 6 && sy < 11) {
                f.setMomentum(i, (i % 5) * 0.25f, -(i % 3) * 1.5f, (i % 2) * 0.75f);
                f.setPressure(i, (11 - sy) * 10000f);
            }
        }
        col.put(-1, f);
        col.put(3, SectionData.uniform(1.204f * 1005f * 288f, 1.204f));
        return col;
    }

    private static byte[] v5FixtureBlob() throws IOException {
        try (InputStream in = SectionCodecTest.class.getResourceAsStream("/fixtures/orge-column-v5.bin")) {
            assertNotNull(in, "fixture fixtures/orge-column-v5.bin must be on the test classpath");
            return in.readAllBytes();
        }
    }

    /** Per-cell mass/E/material/momentum/P of {@code got} equal {@code want}'s, every cell is a
     *  one-component cell, and the grand mass + E totals agree exactly. */
    private static void assertOneComponentEqual(Map<Integer, SectionData> want,
                                                Map<Integer, SectionData> got) {
        assertEquals(want.keySet(), got.keySet(), "same sections");
        double wantM = 0, gotM = 0, wantE = 0, gotE = 0;
        for (int y : want.keySet()) {
            SectionData w = want.get(y), g = got.get(y);
            assertEquals(w.form(), g.form(), "section " + y + " form preserved");
            assertFalse(g.hasSlots(), "section " + y + ": migrated cells carry no composition layer");
            for (int i = 0; i < SectionData.CELLS; i++) {
                assertEquals(w.massAt(i), g.massAt(i), "s" + y + " mass[" + i + "]");
                assertEquals(w.enthalpyAt(i), g.enthalpyAt(i), "s" + y + " E[" + i + "]");
                assertEquals(w.materialAt(i), g.materialAt(i), "s" + y + " material[" + i + "]");
                assertEquals(w.momXAt(i), g.momXAt(i), "s" + y + " px[" + i + "]");
                assertEquals(w.momYAt(i), g.momYAt(i), "s" + y + " py[" + i + "]");
                assertEquals(w.momZAt(i), g.momZAt(i), "s" + y + " pz[" + i + "]");
                assertEquals(w.pAt(i), g.pAt(i), "s" + y + " P[" + i + "]");
                assertEquals(g.massAt(i), g.cellMassAt(i), "s" + y + " one-component: M = m_0 at " + i);
                for (int k = 1; k < SectionData.SLOTS; k++) {
                    assertEquals(0f, g.slotMassAt(k, i), "s" + y + " slot " + k + " empty at " + i);
                    assertEquals(MaterialPalette.VACUUM_ID, g.slotMaterialAt(k, i));
                }
                wantM += w.massAt(i); gotM += g.cellMassAt(i);
                wantE += w.enthalpyAt(i); gotE += g.enthalpyAt(i);
            }
        }
        assertEquals(wantM, gotM, "grand mass total identical");
        assertEquals(wantE, gotE, "grand E total identical");
        assertTrue(wantM > 0 && wantE > 0, "fixture is non-trivial");
    }

    @Test
    void v5FixtureLoadsAsOneComponentCellsWithMassAndEnergyUnchanged() throws IOException {
        byte[] blob = v5FixtureBlob();
        assertEquals(SectionCodec.ONE_COMPONENT_VERSION, blob[0], "fixture is in the OLD (v5) format");
        assertNotEquals(SectionCodec.ONE_COMPONENT_VERSION, SectionCodec.FORMAT_VERSION,
                "the on-disk format was bumped past the one-material v5");

        NavigableMap<Integer, SectionData> migrated = SectionCodec.readColumn(blob);
        assertOneComponentEqual(v5FixtureColumn(), migrated);

        // The next save writes the NEW format, and that migrated world reloads identically.
        byte[] resaved = SectionCodec.writeColumn(migrated);
        assertEquals(SectionCodec.FORMAT_VERSION, resaved[0], "a migrated column re-saves in the new format");
        assertOneComponentEqual(v5FixtureColumn(), SectionCodec.readColumn(resaved));
    }

    // -------------------------------------------------------------------------
    // Test 6d: multi-component sections round-trip save→load exactly (issue #27)
    // -------------------------------------------------------------------------

    @Test
    void multiComponentSectionRoundTripsExactly() throws IOException {
        // Wet sand under a wet-stone/air mix: slot 0 = host, slots 1..3 = pore water / air / a 4th species.
        SectionData s = SectionData.uniform(0f, 0f);
        for (int i = 0; i < SectionData.CELLS; i++) {
            s.setMaterialAt(i, (i & 1) == 0 ? SAND : STONE);
            s.setMass(i, 1600f + (i % 11) * 0.25f);
            s.setEnthalpy(i, 4.0e8f + i * 3.5f);
            s.setMomentum(i, i * 0.01f, -i * 0.02f, 0f);
            s.setPressure(i, 101325f + i);
            if (i % 3 != 0) s.setSlot(1, i, WATER, 300f + (i % 17) * 0.5f);   // pore water
            if (i % 5 == 0) s.setSlot(2, i, AIR, 0.05f + (i % 7) * 0.001f);  // entrained air
        }
        s.setSlot(3, 4095, WATER, 1.5f);   // a fourth occupied slot (unordered, duplicate species allowed)
        s.setSlot(3, 17, SAND, 2.25f);

        Map<Integer, SectionData> column = new TreeMap<>();
        column.put(-2, s);
        column.put(9, SectionData.uniform(1.204f * 1005f * 288f, 1.204f)); // a one-component neighbour
        byte[] blob = SectionCodec.writeColumn(column);
        assertEquals(SectionCodec.FORMAT_VERSION, blob[0]);

        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(-2);
        assertTrue(r.hasSlots(), "composition layer survives save→load");
        double wantM = 0, gotM = 0;
        for (int i = 0; i < SectionData.CELLS; i++) {
            assertEquals(s.materialAt(i), r.materialAt(i), "slot 0 id [" + i + "]");
            assertEquals(s.massAt(i), r.massAt(i), "slot 0 mass [" + i + "]");
            assertEquals(s.enthalpyAt(i), r.enthalpyAt(i), "E [" + i + "]");
            assertEquals(s.momYAt(i), r.momYAt(i), "py [" + i + "]");
            assertEquals(s.pAt(i), r.pAt(i), "P [" + i + "]");
            for (int k = 1; k < SectionData.SLOTS; k++) {
                assertEquals(s.slotMaterialAt(k, i), r.slotMaterialAt(k, i), "slot " + k + " id [" + i + "]");
                assertEquals(s.slotMassAt(k, i), r.slotMassAt(k, i), "slot " + k + " mass [" + i + "]");
            }
            assertEquals(s.cellMassAt(i), r.cellMassAt(i), "cell mass M [" + i + "]");
            wantM += s.cellMassAt(i); gotM += r.cellMassAt(i);
        }
        assertEquals(wantM, gotM, "grand mass total identical");
        assertEquals(WATER, r.slotMaterialAt(1, 1));
        assertEquals(300.5f, r.slotMassAt(1, 1));
        assertEquals(AIR, r.slotMaterialAt(2, 5));
        assertEquals(SAND, r.slotMaterialAt(3, 17));
        assertEquals(2.25f, r.slotMassAt(3, 17));
        assertFalse(back.get(9).hasSlots(), "a one-component section stays layer-free");
        assertEquals(1.204f, back.get(9).cellMassAt(0));
        // Exact: a second save of the loaded column is byte-identical to the first.
        assertArrayEquals(blob, SectionCodec.writeColumn(back), "save→load→save is byte-identical");
    }

    /** A v6 column of one FULL section (palette [vacuum, sand, water]) written up to and including
     *  its composition flag byte = 1; the caller writes the composition payload. */
    private static DataOutputStream compositionPrefix(ByteArrayOutputStream bos) throws IOException {
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(SectionCodec.FORMAT_VERSION);
        out.writeShort(1);
        out.writeInt(0);
        out.writeByte(1);                                  // FULL
        byte[] e = SectionCodec.deflate(SectionCodec.floatsToBytes(new float[SectionData.CELLS]));
        out.writeInt(e.length); out.write(e);
        out.writeInt(e.length); out.write(e);
        out.writeByte(1);                                  // materials
        out.writeShort(3);
        out.writeUTF(MaterialPalette.VACUUM_ID.toString());
        out.writeUTF(SAND.toString());
        out.writeUTF(WATER.toString());
        byte[] ix = SectionCodec.deflate(SectionCodec.charsToBytes(new char[SectionData.CELLS]));
        out.writeInt(ix.length); out.write(ix);
        out.writeByte(0);                                  // no momentum
        out.writeByte(0);                                  // no pressure
        out.writeByte(1);                                  // composition present
        return out;
    }

    @Test
    void compositionBlockWithOutOfPaletteIndexIsRejected() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = compositionPrefix(bos)) {
            char[] bad = new char[SectionData.SLOT_CELLS];
            bad[5] = 7;                                    // past the 3-entry palette
            byte[] sx = SectionCodec.deflate(SectionCodec.charsToBytes(bad));
            out.writeInt(sx.length); out.write(sx);
            byte[] sm = SectionCodec.deflate(SectionCodec.floatsToBytes(new float[SectionData.SLOT_CELLS]));
            out.writeInt(sm.length); out.write(sm);
        }
        IOException ex = assertThrows(IOException.class, () -> SectionCodec.readColumn(bos.toByteArray()));
        assertTrue(ex.getMessage().contains("slot palette index"), ex.getMessage());
    }

    @Test
    void compositionBlockWithNegativeLengthsIsRejected() throws IOException {
        ByteArrayOutputStream ixBos = new ByteArrayOutputStream();
        try (DataOutputStream out = compositionPrefix(ixBos)) {
            out.writeInt(-1);                              // slot-index payload length
        }
        IOException ixEx = assertThrows(IOException.class, () -> SectionCodec.readColumn(ixBos.toByteArray()));
        assertTrue(ixEx.getMessage().contains("negative compressed length"), ixEx.getMessage());

        ByteArrayOutputStream mBos = new ByteArrayOutputStream();
        try (DataOutputStream out = compositionPrefix(mBos)) {
            byte[] sx = SectionCodec.deflate(SectionCodec.charsToBytes(new char[SectionData.SLOT_CELLS]));
            out.writeInt(sx.length); out.write(sx);
            out.writeInt(-1);                              // slot-mass payload length
        }
        IOException mEx = assertThrows(IOException.class, () -> SectionCodec.readColumn(mBos.toByteArray()));
        assertTrue(mEx.getMessage().contains("negative compressed length"), mEx.getMessage());
    }

    @Test
    void compositionBlockWithoutPaletteIsRejected() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(SectionCodec.FORMAT_VERSION);
            out.writeShort(1);
            out.writeInt(0);
            out.writeByte(0);        // UNIFORM
            out.writeFloat(1f);
            out.writeFloat(1f);
            out.writeByte(0);        // no materials
            out.writeByte(0);        // no momentum
            out.writeByte(0);        // no pressure
            out.writeByte(1);        // composition — but no palette to index
        }
        IOException ex = assertThrows(IOException.class, () -> SectionCodec.readColumn(bos.toByteArray()));
        assertTrue(ex.getMessage().contains("without a material palette"), ex.getMessage());
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
