package net.rainbowcreation.orge.section;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
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
    public static final byte FORMAT_VERSION = 4;

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

    /**
     * Packs a {@code char[]} into a big-endian {@code byte[]} (2 bytes per char).
     */
    static byte[] charsToBytes(char[] a) {
        ByteBuffer buf = ByteBuffer.allocate(a.length * 2).order(ByteOrder.BIG_ENDIAN);
        for (char c : a) {
            buf.putChar(c);
        }
        return buf.array();
    }

    /**
     * Unpacks a big-endian {@code byte[]} into a {@code char[]} (2 bytes per char).
     */
    static char[] bytesToChars(byte[] b) {
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        char[] a = new char[b.length / 2];
        for (int i = 0; i < a.length; i++) {
            a[i] = buf.getChar();
        }
        return a;
    }

    // =========================================================================
    // Single section serialization
    // =========================================================================

    /**
     * Writes one {@link SectionData} to {@code out}.
     *
     * <p>Wire format (v3):
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
     * -- material block (v2+; absent in legacy v1 blobs):
     * byte  hasMaterials ; 0 = none, 1 = present
     * -- if hasMaterials == 1:
     * short paletteCount
     * UTF[paletteCount]  ; palette ids (slot 0 = orge:vacuum)
     * int   iLen         ; compressed char[4096] index array length
     * byte[iLen]
     * -- velocity block (v3+; absent in v1/v2 blobs):
     * byte  hasVelocity  ; 0 = none (all cells 0), 1 = present
     * -- if hasVelocity == 1:
     * int   vxLen ; compressed float[4096] velX array length
     * byte[vxLen]
     * int   vyLen ; compressed float[4096] velY array length
     * byte[vyLen]
     * int   vzLen ; compressed float[4096] velZ array length
     * byte[vzLen]
     * -- pressure block (v4+; absent in v1/v2/v3 blobs; INDEPENDENT of the velocity flag):
     * byte  hasPressure  ; 0 = none (all cells 0), 1 = present
     * -- if hasPressure == 1:
     * int   pLen ; compressed float[4096] p array length
     * byte[pLen]
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
        // v2+ material block (uniform for format uniformity; UNIFORM sections never carry materials).
        if (s.hasMaterials()) {
            out.writeByte(1);
            MaterialPalette mp = s.materials();
            out.writeShort(mp.palette().size());
            for (net.minecraft.resources.Identifier id : mp.palette()) {
                out.writeUTF(id.toString());
            }
            byte[] iComp = deflate(charsToBytes(mp.indices()));
            out.writeInt(iComp.length);
            out.write(iComp);
        } else {
            out.writeByte(0);
        }
        // v3 velocity block.
        if (s.hasVelocity()) {
            out.writeByte(1);
            byte[] vxComp = deflate(floatsToBytes(s.velXArray()));
            out.writeInt(vxComp.length);
            out.write(vxComp);
            byte[] vyComp = deflate(floatsToBytes(s.velYArray()));
            out.writeInt(vyComp.length);
            out.write(vyComp);
            byte[] vzComp = deflate(floatsToBytes(s.velZArray()));
            out.writeInt(vzComp.length);
            out.write(vzComp);
        } else {
            out.writeByte(0);
        }
        // v4 pressure block (independent gate; written AFTER velocity so order is
        // materials -> velocity -> pressure).
        if (s.hasPressure()) {
            out.writeByte(1);
            byte[] pComp = deflate(floatsToBytes(s.pArray()));
            out.writeInt(pComp.length);
            out.write(pComp);
        } else {
            out.writeByte(0);
        }
    }

    /**
     * Reads one {@link SectionData} from {@code in} (full v4 format: materials + velocity + pressure).
     *
     * @throws IOException if the form byte is unrecognised or data is corrupt
     */
    public static SectionData readSection(DataInputStream in) throws IOException {
        return readSection(in, true, true, true);
    }

    /**
     * Reads one {@link SectionData} from {@code in}, optionally including the trailing v2 material
     * block. v1 blobs carry no material block ({@code withMaterials == false}); v2 blobs do.
     * Velocity/pressure are NOT read — used by legacy callers.
     *
     * @throws IOException if the form byte is unrecognised or data is corrupt
     */
    static SectionData readSection(DataInputStream in, boolean withMaterials) throws IOException {
        return readSection(in, withMaterials, false, false);
    }

    /**
     * Reads one {@link SectionData} from {@code in}, optionally including the trailing v2 material
     * block and v3 velocity block. Pressure is NOT read — used by legacy v3 callers.
     *
     * @param withMaterials whether to read the v2+ material block
     * @param withVelocity  whether to read the v3+ velocity block
     * @throws IOException  if the form byte is unrecognised or data is corrupt
     */
    static SectionData readSection(DataInputStream in, boolean withMaterials, boolean withVelocity)
            throws IOException {
        return readSection(in, withMaterials, withVelocity, false);
    }

    /**
     * Reads one {@link SectionData} from {@code in}, optionally including the trailing v2 material
     * block, v3 velocity block, and v4 pressure block.
     *
     * @param withMaterials whether to read the v2+ material block
     * @param withVelocity  whether to read the v3+ velocity block
     * @param withPressure  whether to read the v4+ pressure block
     * @throws IOException  if the form byte is unrecognised or data is corrupt
     */
    static SectionData readSection(DataInputStream in, boolean withMaterials, boolean withVelocity,
                                   boolean withPressure) throws IOException {
        byte form = in.readByte();
        SectionData section;
        if (form == FORM_UNIFORM) {
            float t = in.readFloat();
            float m = in.readFloat();
            section = SectionData.uniform(t, m);
        } else if (form == FORM_FULL) {
            int tLen = in.readInt();
            if (tLen < 0) throw new IOException("corrupt section: negative compressed length " + tLen);
            byte[] tComp = in.readNBytes(tLen);
            float[] t = bytesToFloats(inflate(tComp, SectionData.CELLS * 4));

            int mLen = in.readInt();
            if (mLen < 0) throw new IOException("corrupt section: negative compressed length " + mLen);
            byte[] mComp = in.readNBytes(mLen);
            float[] m = bytesToFloats(inflate(mComp, SectionData.CELLS * 4));

            section = SectionData.full(t, m);
        } else {
            throw new IOException("unknown section form: " + (form & 0xFF));
        }
        if (withMaterials) {
            byte hasMaterials = in.readByte();
            if (hasMaterials == 1) {
                int paletteCount = in.readShort() & 0xFFFF;
                List<net.minecraft.resources.Identifier> paletteList = new ArrayList<>(paletteCount);
                for (int i = 0; i < paletteCount; i++) {
                    paletteList.add(net.minecraft.resources.Identifier.parse(in.readUTF()));
                }
                int iLen = in.readInt();
                if (iLen < 0) throw new IOException("corrupt section: negative compressed length " + iLen);
                byte[] iComp = in.readNBytes(iLen);
                char[] indices = bytesToChars(inflate(iComp, SectionData.CELLS * 2));
                section.adoptMaterials(new MaterialPalette(paletteList, indices));
            }
        }
        if (withVelocity) {
            byte hasVelocity = in.readByte();
            if (hasVelocity == 1) {
                int vxLen = in.readInt();
                if (vxLen < 0) throw new IOException("corrupt section: negative compressed length " + vxLen);
                float[] vx = bytesToFloats(inflate(in.readNBytes(vxLen), SectionData.CELLS * 4));

                int vyLen = in.readInt();
                if (vyLen < 0) throw new IOException("corrupt section: negative compressed length " + vyLen);
                float[] vy = bytesToFloats(inflate(in.readNBytes(vyLen), SectionData.CELLS * 4));

                int vzLen = in.readInt();
                if (vzLen < 0) throw new IOException("corrupt section: negative compressed length " + vzLen);
                float[] vz = bytesToFloats(inflate(in.readNBytes(vzLen), SectionData.CELLS * 4));

                System.arraycopy(vx, 0, section.velXArray(), 0, SectionData.CELLS);
                System.arraycopy(vy, 0, section.velYArray(), 0, SectionData.CELLS);
                System.arraycopy(vz, 0, section.velZArray(), 0, SectionData.CELLS);
            }
        }
        if (withPressure) {
            byte hasPressure = in.readByte();
            if (hasPressure == 1) {
                int pLen = in.readInt();
                if (pLen < 0) throw new IOException("corrupt section: negative compressed length " + pLen);
                float[] p = bytesToFloats(inflate(in.readNBytes(pLen), SectionData.CELLS * 4));
                System.arraycopy(p, 0, section.pArray(), 0, SectionData.CELLS);
            }
        }
        return section;
    }

    // =========================================================================
    // Column (de)serialization
    // =========================================================================

    /**
     * Serializes a full chunk column (sectionY → SectionData) to a byte blob.
     *
     * <p>Wire format:
     * <pre>
     * byte  version       ; FORMAT_VERSION (4); legacy v1 (no material/velocity/pressure), v2 (no
     *                     ; velocity/pressure) and v3 (no pressure) blobs are also accepted on read
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
        if (version < 1 || version > FORMAT_VERSION) {
            throw new IOException("unsupported column format version: " + version);
        }
        boolean withMaterials = version >= 2;
        boolean withVelocity  = version >= 3;
        boolean withPressure  = version >= 4;
        int count = in.readShort() & 0xFFFF;
        TreeMap<Integer, SectionData> result = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int sectionY = in.readInt();
            SectionData section = readSection(in, withMaterials, withVelocity, withPressure);
            result.put(sectionY, section);
        }
        return result;
    }
}
