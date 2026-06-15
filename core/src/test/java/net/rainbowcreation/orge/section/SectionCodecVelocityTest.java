package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.Map;
import java.util.NavigableMap;
import static org.junit.jupiter.api.Assertions.*;

class SectionCodecVelocityTest {
    @Test
    void v3RoundTripsVelocity() throws IOException {
        SectionData s = SectionData.uniform(300f, 1000f);
        s.setMomentum(7, 1.25f, -3.5f, 0.5f);
        byte[] blob = SectionCodec.writeColumn(Map.of(5, s));
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(5);
        assertEquals(1.25f, r.momXAt(7));
        assertEquals(-3.5f, r.momYAt(7));
        assertEquals(0.5f, r.momZAt(7));
        assertEquals(0f, r.momXAt(8), "untouched cell stays 0");
    }

    @Test
    void v2BlobLoadsWithZeroVelocity() throws IOException {
        // A pre-velocity (v2) UNIFORM-section column blob must read back with velocity == 0.
        byte[] v2blob = LegacyBlobs.v2SingleUniformSection(5, 300f, 1000f);
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(v2blob);
        assertEquals(0f, back.get(5).momXAt(0), "v2 blob -> velocity defaults to 0");
        assertEquals(300f, back.get(5).enthalpyAt(0), "v2 temperature still read");
    }

    @Test
    void v4RoundTripsPressure() throws IOException {
        SectionData s = SectionData.uniform(300f, 1000f);
        s.setPressure(11, 1234.5f);
        s.setMomentum(7, 1.0f, -2.0f, 3.0f);  // p is independent of velocity
        byte[] blob = SectionCodec.writeColumn(Map.of(5, s));
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(5);
        assertEquals(1234.5f, r.pAt(11), "pressure round-trips at v4");
        assertEquals(0f, r.pAt(12), "untouched cell pressure stays 0");
        assertEquals(1.0f, r.momXAt(7), "velocity still round-trips alongside pressure");
    }

    @Test
    void v4RoundTripsPressureWithoutVelocity() throws IOException {
        // Pressure carried with NO velocity layer (independent gates).
        SectionData s = SectionData.uniform(300f, 1000f);
        s.setPressure(3, 500f);
        byte[] blob = SectionCodec.writeColumn(Map.of(2, s));
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(2);
        assertEquals(500f, r.pAt(3), "pressure round-trips with no velocity layer present");
        assertEquals(0f, r.momXAt(3), "velocity defaults to 0 when never written");
    }

    @Test
    void v3BlobLoadsWithZeroPressure() throws IOException {
        // A pre-pressure (v3) column blob (velocity present, NO pressure block) must read back p == 0.
        byte[] v3blob = LegacyBlobs.v3SingleUniformSectionNoVelocity(5, 300f, 1000f);
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(v3blob);
        assertEquals(0f, back.get(5).pAt(0), "v3 blob -> pressure defaults to 0");
        assertEquals(300f, back.get(5).enthalpyAt(0), "v3 temperature still read");
        assertEquals(0f, back.get(5).momXAt(0), "v3 velocity-absent reads 0");
    }

    /** Hand-builds a v2 column blob (version byte = 2) with one UNIFORM section, matching the
     *  documented v2 wire format: [byte v=2][short count=1]( [int sectionY][byte FORM_UNIFORM=0]
     *  [float uniformT][float uniformM][byte hasMaterials=0] ). */
    static final class LegacyBlobs {
        static byte[] v2SingleUniformSection(int sectionY, float t, float m) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(2);            // version
            out.writeShort(1);           // section count
            out.writeInt(sectionY);
            out.writeByte(0);            // FORM_UNIFORM
            out.writeFloat(t);
            out.writeFloat(m);
            out.writeByte(0);            // hasMaterials = 0
            out.flush();
            return bos.toByteArray();
        }

        /** A v3 column blob (version byte = 3) with one UNIFORM section, material+velocity flags both
         *  0, and NO pressure block: [byte v=3][short count=1]( [int sectionY][byte FORM_UNIFORM=0]
         *  [float uniformT][float uniformM][byte hasMaterials=0][byte hasMomentum=0] ). */
        static byte[] v3SingleUniformSectionNoVelocity(int sectionY, float t, float m) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(3);            // version
            out.writeShort(1);           // section count
            out.writeInt(sectionY);
            out.writeByte(0);            // FORM_UNIFORM
            out.writeFloat(t);
            out.writeFloat(m);
            out.writeByte(0);            // hasMaterials = 0
            out.writeByte(0);            // hasMomentum = 0 (NO pressure block follows in v3)
            out.flush();
            return bos.toByteArray();
        }
    }
}
