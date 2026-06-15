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
    public static final byte FORMAT_VERSION = 5;

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
     * <p>Wire format (v5; law §7 extensive state — persisted enthalpy E [J] and momentum
     * p⃗ [kg·m/s], NOT raw temperature/velocity):
     * <pre>
     * byte  form       ; 0 = UNIFORM, 1 = FULL
     * -- UNIFORM:
     * float enthalpy E [J]
     * float mass
     * -- FULL:
     * int   tLen ; compressed enthalpy E [J] array length
     * byte[tLen]
     * int   mLen ; compressed mass array length
     * byte[mLen]
     * -- material block (v5 always emits the flag byte):
     * byte  hasMaterials ; 0 = none, 1 = present
     * -- if hasMaterials == 1:
     * short paletteCount
     * UTF[paletteCount]  ; palette ids (slot 0 = orge:vacuum)
     * int   iLen         ; compressed char[4096] index array length
     * byte[iLen]
     * -- momentum block (v5; the flag byte is always emitted):
     * byte  hasMomentum  ; 0 = none (all cells 0), 1 = present
     * -- if hasMomentum == 1:
     * int   pxLen ; compressed float[4096] momX array length [kg·m/s]
     * byte[pxLen]
     * int   pyLen ; compressed float[4096] momY array length [kg·m/s]
     * byte[pyLen]
     * int   pzLen ; compressed float[4096] momZ array length [kg·m/s]
     * byte[pzLen]
     * -- pressure block (v5; INDEPENDENT of the momentum flag; flag byte always emitted):
     * byte  hasPressure  ; 0 = none (all cells 0), 1 = present
     * -- if hasPressure == 1:
     * int   pLen ; compressed float[4096] P [Pa] array length
     * byte[pLen]
     * </pre></p>
     */
    public static void writeSection(DataOutputStream out, SectionData s) throws IOException {
        if (s.form() == SectionData.Form.UNIFORM) {
            out.writeByte(FORM_UNIFORM);
            out.writeFloat(s.uniformEnthalpy());
            out.writeFloat(s.uniformMass());
        } else {
            out.writeByte(FORM_FULL);
            byte[] tComp = deflate(floatsToBytes(s.enthalpyArray()));
            out.writeInt(tComp.length);
            out.write(tComp);
            byte[] mComp = deflate(floatsToBytes(s.massArray()));
            out.writeInt(mComp.length);
            out.write(mComp);
        }
        // material block (uniform for format uniformity; UNIFORM sections never carry materials).
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
        // momentum block (extensive p⃗ = m·u, [kg·m/s]; law §7).
        if (s.hasMomentum()) {
            out.writeByte(1);
            byte[] vxComp = deflate(floatsToBytes(s.momXArray()));
            out.writeInt(vxComp.length);
            out.write(vxComp);
            byte[] vyComp = deflate(floatsToBytes(s.momYArray()));
            out.writeInt(vyComp.length);
            out.write(vyComp);
            byte[] vzComp = deflate(floatsToBytes(s.momZArray()));
            out.writeInt(vzComp.length);
            out.write(vzComp);
        } else {
            out.writeByte(0);
        }
        // pressure block (independent gate; written AFTER momentum so order is
        // materials -> momentum -> pressure).
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
     * Reads one {@link SectionData} from {@code in} (v5 format: enthalpy/mass + the always-present
     * material, momentum, and pressure flag blocks). The per-block flag byte (0 = all-zero channel,
     * 1 = present) still gates each block's payload — that is NOT version gating, it lives within v5.
     *
     * @throws IOException if the form byte is unrecognised or data is corrupt
     */
    public static SectionData readSection(DataInputStream in) throws IOException {
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
        {
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
        {
            byte hasMomentum = in.readByte();
            if (hasMomentum == 1) {
                int vxLen = in.readInt();
                if (vxLen < 0) throw new IOException("corrupt section: negative compressed length " + vxLen);
                float[] vx = bytesToFloats(inflate(in.readNBytes(vxLen), SectionData.CELLS * 4));

                int vyLen = in.readInt();
                if (vyLen < 0) throw new IOException("corrupt section: negative compressed length " + vyLen);
                float[] vy = bytesToFloats(inflate(in.readNBytes(vyLen), SectionData.CELLS * 4));

                int vzLen = in.readInt();
                if (vzLen < 0) throw new IOException("corrupt section: negative compressed length " + vzLen);
                float[] vz = bytesToFloats(inflate(in.readNBytes(vzLen), SectionData.CELLS * 4));

                System.arraycopy(vx, 0, section.momXArray(), 0, SectionData.CELLS);
                System.arraycopy(vy, 0, section.momYArray(), 0, SectionData.CELLS);
                System.arraycopy(vz, 0, section.momZArray(), 0, SectionData.CELLS);
            }
        }
        {
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
     * byte  version       ; FORMAT_VERSION (5). ONLY v5 is accepted on read; older blobs
     *                     ; (v1–v4, which stored raw temperature/velocity) are REJECTED — there
     *                     ; is no migration (law §7 schema change: raw T/v → extensive E/momentum).
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
            throw new IOException("unsupported ORGE column format version " + version
                    + " (expected " + FORMAT_VERSION + "); the on-disk state schema changed"
                    + " (raw temperature/velocity → extensive enthalpy/momentum, law §7) — there is"
                    + " no migration: delete world/orge and start a fresh world.");
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
