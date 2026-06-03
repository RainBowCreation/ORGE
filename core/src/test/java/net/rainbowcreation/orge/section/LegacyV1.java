package net.rainbowcreation.orge.section;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Test helper writing the EXACT pre-D2 (v1) column wire format: a version byte (1), a short section
 * count, then per section an int sectionY and a UNIFORM payload (form byte 0, float t, float m), with
 * NO material block appended. Used to verify {@link SectionCodec} still reads legacy blobs.
 */
final class LegacyV1 {
    private LegacyV1() {}

    static byte[] uniformColumn(int sectionY, float t, float m) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(1);        // version = 1 (legacy)
            out.writeShort(1);       // count = 1
            out.writeInt(sectionY);
            out.writeByte(0);        // form = UNIFORM
            out.writeFloat(t);
            out.writeFloat(m);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return bos.toByteArray();
    }
}
