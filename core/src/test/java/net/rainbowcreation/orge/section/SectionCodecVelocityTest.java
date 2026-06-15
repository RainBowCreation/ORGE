package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.Map;
import java.util.NavigableMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * v5 codec tests for the persisted momentum + pressure channels (law §7: extensive E + momentum,
 * NOT raw temperature/velocity). Older blobs (v1–v4, raw temperature/velocity) are REJECTED — there
 * is no migration. (T2 S3.)
 */
class SectionCodecVelocityTest {
    @Test
    void momentumRoundTripsV5() throws IOException {
        SectionData s = SectionData.uniform(300f, 1000f);
        s.setMomentum(7, 1.25f, -3.5f, 0.5f); // extensive momentum p = m·u [kg·m/s]
        byte[] blob = SectionCodec.writeColumn(Map.of(5, s));
        assertEquals(SectionCodec.FORMAT_VERSION, blob[0], "blob must carry the v5 version byte");
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(5);
        assertEquals(1.25f, r.momXAt(7), "stored momentum (extensive) round-trips");
        assertEquals(-3.5f, r.momYAt(7));
        assertEquals(0.5f, r.momZAt(7));
        assertEquals(0f, r.momXAt(8), "untouched cell stays 0");
        assertEquals(300f, r.enthalpyAt(7), "enthalpy E round-trips");
    }

    @Test
    void v2BlobIsRejected() {
        // A pre-v5 (v2) blob stored RAW temperature/velocity; reading it as enthalpy/momentum would
        // be semantically wrong (law §7). There is NO migration — it must be rejected.
        byte[] v2blob = LegacyBlobs.v2SingleUniformSection(5, 300f, 1000f);
        IOException ex = assertThrows(IOException.class, () -> SectionCodec.readColumn(v2blob),
                "v2 blob must be rejected (no migration)");
        assertTrue(ex.getMessage().contains("fresh world"),
                "reject message must tell the user to start a fresh world, was: " + ex.getMessage());
    }

    @Test
    void v3BlobIsRejected() {
        // A pre-v5 (v3) blob (velocity present, no pressure) is likewise rejected — raw T/v schema.
        byte[] v3blob = LegacyBlobs.v3SingleUniformSectionNoVelocity(5, 300f, 1000f);
        IOException ex = assertThrows(IOException.class, () -> SectionCodec.readColumn(v3blob),
                "v3 blob must be rejected (no migration)");
        assertTrue(ex.getMessage().contains("fresh world"),
                "reject message must tell the user to start a fresh world, was: " + ex.getMessage());
    }

    @Test
    void v4BlobIsRejected() {
        // A v4 blob (raw temperature/velocity + pressure) predates the law §7 schema; rejected.
        byte[] v4blob = LegacyBlobs.v4SingleUniformSection(5, 300f, 1000f);
        IOException ex = assertThrows(IOException.class, () -> SectionCodec.readColumn(v4blob),
                "v4 blob must be rejected (no migration)");
        assertTrue(ex.getMessage().contains("fresh world"),
                "reject message must tell the user to start a fresh world, was: " + ex.getMessage());
    }

    @Test
    void pressureRoundTripsV5() throws IOException {
        SectionData s = SectionData.uniform(300f, 1000f);
        s.setPressure(11, 1234.5f);
        s.setMomentum(7, 1.0f, -2.0f, 3.0f); // p (pressure) is independent of momentum
        byte[] blob = SectionCodec.writeColumn(Map.of(5, s));
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(5);
        assertEquals(1234.5f, r.pAt(11), "pressure round-trips at v5");
        assertEquals(0f, r.pAt(12), "untouched cell pressure stays 0");
        assertEquals(1.0f, r.momXAt(7), "momentum still round-trips alongside pressure");
    }

    @Test
    void pressureRoundTripsWithoutMomentumV5() throws IOException {
        // Pressure carried with NO momentum layer (independent gates).
        SectionData s = SectionData.uniform(300f, 1000f);
        s.setPressure(3, 500f);
        byte[] blob = SectionCodec.writeColumn(Map.of(2, s));
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(2);
        assertEquals(500f, r.pAt(3), "pressure round-trips with no momentum layer present");
        assertEquals(0f, r.momXAt(3), "momentum defaults to 0 when never written");
    }

    /** Hand-builds pre-v5 column blobs (raw temperature/velocity schema) to prove they are rejected. */
    static final class LegacyBlobs {
        /** v2: [byte v=2][short count=1]( [int sectionY][FORM_UNIFORM=0][float T][float M][hasMaterials=0] ). */
        static byte[] v2SingleUniformSection(int sectionY, float t, float m) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) {
                out.writeByte(2);            // version
                out.writeShort(1);           // section count
                out.writeInt(sectionY);
                out.writeByte(0);            // FORM_UNIFORM
                out.writeFloat(t);
                out.writeFloat(m);
                out.writeByte(0);            // hasMaterials = 0
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return bos.toByteArray();
        }

        /** v3: as v2 plus a trailing hasMomentum=0 flag (NO pressure block). */
        static byte[] v3SingleUniformSectionNoVelocity(int sectionY, float t, float m) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) {
                out.writeByte(3);            // version
                out.writeShort(1);           // section count
                out.writeInt(sectionY);
                out.writeByte(0);            // FORM_UNIFORM
                out.writeFloat(t);
                out.writeFloat(m);
                out.writeByte(0);            // hasMaterials = 0
                out.writeByte(0);            // hasMomentum = 0 (NO pressure block in v3)
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return bos.toByteArray();
        }

        /** v4: as v3 plus a trailing hasPressure=0 flag. */
        static byte[] v4SingleUniformSection(int sectionY, float t, float m) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) {
                out.writeByte(4);            // version
                out.writeShort(1);           // section count
                out.writeInt(sectionY);
                out.writeByte(0);            // FORM_UNIFORM
                out.writeFloat(t);
                out.writeFloat(m);
                out.writeByte(0);            // hasMaterials = 0
                out.writeByte(0);            // hasMomentum = 0
                out.writeByte(0);            // hasPressure = 0
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return bos.toByteArray();
        }
    }
}
