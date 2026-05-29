package net.rainbowcreation.orge.section;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Binary (de)serialization of {@link SectionData} sections and chunk columns (DESIGN.md §5).
 *
 * <p>All multi-byte values are big-endian. FULL section arrays are deflate-compressed.
 * Column blobs carry a version byte followed by a short section count and per-section payloads.</p>
 */
public final class SectionCodec {

    /** Wire format version for column blobs. */
    public static final byte FORMAT_VERSION = 1;

    private static final byte FORM_UNIFORM = 0;
    private static final byte FORM_FULL    = 1;

    private SectionCodec() {}

    // =========================================================================
    // Low-level helpers
    // =========================================================================

    /**
     * Compresses {@code raw} with {@link Deflater#DEFAULT_COMPRESSION}.
     */
    static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 64);
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buf);
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            d.end();
        }
    }

    /**
     * Decompresses {@code comp} expecting exactly {@code expectedLen} bytes of output.
     *
     * @throws IOException if the inflated length doesn't match, or if the compressed data is corrupt
     */
    static byte[] inflate(byte[] comp, int expectedLen) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(comp);
            byte[] out = new byte[expectedLen];
            int total = 0;
            try {
                while (!inf.finished() && total < expectedLen) {
                    int n = inf.inflate(out, total, expectedLen - total);
                    if (n == 0 && !inf.finished()) {
                        break; // no-dict deflate: inflate() returning 0 while not finished means corrupt input
                    }
                    total += n;
                }
            } catch (DataFormatException e) {
                throw new IOException("corrupt section: decompression failed", e);
            }
            if (total != expectedLen) {
                throw new IOException("corrupt section: expected " + expectedLen + " bytes, got " + total);
            }
            return out;
        } finally {
            inf.end();
        }
    }

    /**
     * Packs a {@code float[]} into a big-endian {@code byte[]}.
     */
    static byte[] floatsToBytes(float[] a) {
        ByteBuffer buf = ByteBuffer.allocate(a.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (float v : a) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    /**
     * Unpacks a big-endian {@code byte[]} into a {@code float[]}.
     */
    static float[] bytesToFloats(byte[] b) {
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        float[] a = new float[b.length / 4];
        for (int i = 0; i < a.length; i++) {
            a[i] = buf.getFloat();
        }
        return a;
    }

    // =========================================================================
    // Single section serialization
    // =========================================================================

    /**
     * Writes one {@link SectionData} to {@code out}.
     *
     * <p>Wire format:
     * <pre>
     * byte  form       ; 0 = UNIFORM, 1 = FULL
     * -- UNIFORM:
     * float temperature
     * float mass
     * -- FULL:
     * int   tLen ; compressed temperature array length
     * byte[tLen]
     * int   mLen ; compressed mass array length
     * byte[mLen]
     * </pre></p>
     */
    public static void writeSection(DataOutputStream out, SectionData s) throws IOException {
        if (s.form() == SectionData.Form.UNIFORM) {
            out.writeByte(FORM_UNIFORM);
            out.writeFloat(s.uniformTemperature());
            out.writeFloat(s.uniformMass());
        } else {
            out.writeByte(FORM_FULL);
            byte[] tComp = deflate(floatsToBytes(s.temperatureArray()));
            out.writeInt(tComp.length);
            out.write(tComp);
            byte[] mComp = deflate(floatsToBytes(s.massArray()));
            out.writeInt(mComp.length);
            out.write(mComp);
        }
    }

    /**
     * Reads one {@link SectionData} from {@code in}.
     *
     * @throws IOException if the form byte is unrecognised or data is corrupt
     */
    public static SectionData readSection(DataInputStream in) throws IOException {
        byte form = in.readByte();
        if (form == FORM_UNIFORM) {
            float t = in.readFloat();
            float m = in.readFloat();
            return SectionData.uniform(t, m);
        } else if (form == FORM_FULL) {
            int tLen = in.readInt();
            if (tLen < 0) throw new IOException("corrupt section: negative compressed length " + tLen);
            byte[] tComp = in.readNBytes(tLen);
            float[] t = bytesToFloats(inflate(tComp, SectionData.CELLS * 4));

            int mLen = in.readInt();
            if (mLen < 0) throw new IOException("corrupt section: negative compressed length " + mLen);
            byte[] mComp = in.readNBytes(mLen);
            float[] m = bytesToFloats(inflate(mComp, SectionData.CELLS * 4));

            return SectionData.full(t, m);
        } else {
            throw new IOException("unknown section form: " + (form & 0xFF));
        }
    }

    // =========================================================================
    // Column (de)serialization
    // =========================================================================

    /**
     * Serializes a full chunk column (sectionY → SectionData) to a byte blob.
     *
     * <p>Wire format:
     * <pre>
     * byte  version       ; FORMAT_VERSION (1)
     * short sectionCount
     * repeat sectionCount (sorted by sectionY ascending):
     *   int   sectionY
     *   &lt;section payload&gt;
     * </pre></p>
     *
     * @param column map of sectionY to section data; may be empty
     * @return the serialized blob
     */
    public static byte[] writeColumn(Map<Integer, SectionData> column) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(FORMAT_VERSION);
        out.writeShort(column.size());
        // Sort by key for deterministic output
        for (Map.Entry<Integer, SectionData> e : new TreeMap<>(column).entrySet()) {
            out.writeInt(e.getKey());
            writeSection(out, e.getValue());
        }
        out.flush();
        return bos.toByteArray();
    }

    /**
     * Deserializes a chunk column blob produced by {@link #writeColumn}.
     *
     * @param blob the serialized byte array
     * @return a {@link NavigableMap} of sectionY → SectionData, sorted ascending by key
     * @throws IOException if the version is unsupported, or data is corrupt
     */
    public static NavigableMap<Integer, SectionData> readColumn(byte[] blob) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob));
        int version = in.readByte() & 0xFF;
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported column format version: " + version);
        }
        int count = in.readShort() & 0xFFFF;
        TreeMap<Integer, SectionData> result = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int sectionY = in.readInt();
            SectionData section = readSection(in);
            result.put(sectionY, section);
        }
        return result;
    }
}
