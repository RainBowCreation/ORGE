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
        s.setVelocity(7, 1.25f, -3.5f, 0.5f);
        byte[] blob = SectionCodec.writeColumn(Map.of(5, s));
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(5);
        assertEquals(1.25f, r.velXAt(7));
        assertEquals(-3.5f, r.velYAt(7));
        assertEquals(0.5f, r.velZAt(7));
        assertEquals(0f, r.velXAt(8), "untouched cell stays 0");
    }

    @Test
    void v2BlobLoadsWithZeroVelocity() throws IOException {
        // A pre-velocity (v2) UNIFORM-section column blob must read back with velocity == 0.
        byte[] v2blob = LegacyBlobs.v2SingleUniformSection(5, 300f, 1000f);
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(v2blob);
        assertEquals(0f, back.get(5).velXAt(0), "v2 blob -> velocity defaults to 0");
        assertEquals(300f, back.get(5).temperatureAt(0), "v2 temperature still read");
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
    }
}
