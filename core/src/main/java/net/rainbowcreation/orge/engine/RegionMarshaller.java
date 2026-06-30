package net.rainbowcreation.orge.engine;

import java.util.ArrayList;
import java.util.List;

/** Packs ColumnTasks into the flat arrays orgeStepWorld expects and slices results back.
 *  Column arrays are CHUNK_N long in engine order (idx = x + 16*y + 6144*z).
 *
 *  <p>This class owns the engine-column bijection: {@link #colIdx(int, int, int)} is the single source of
 *  truth for {@code (x, engineY, z) -> flat index}, and {@link #colX}/{@link #colY}/{@link #colZ} are its
 *  inverse. Callers address cells by coordinate through these helpers instead of re-deriving the
 *  {@code x + 16*y + 6144*z} / {@code (idx/16)%384} / {@code idx/6144} arithmetic by hand. The
 *  section-offset variant lives in {@code ColumnSectionCodec.colIdx} and routes through {@link #colIdx}.</p> */
public final class RegionMarshaller {
    public static final int CHUNK_W = 16, CHUNK_H = 384, CHUNK_D = 16;
    public static final int CHUNK_N = CHUNK_W * CHUNK_H * CHUNK_D; // 98304

    /** Engine-column strides: idx = x + COL_Y_STRIDE*engineY + COL_Z_STRIDE*z. */
    private static final int COL_Y_STRIDE = CHUNK_W;            // 16   (one row of x)
    private static final int COL_Z_STRIDE = CHUNK_W * CHUNK_H;  // 6144 (one x·y plane)

    /** The engine-column bijection (single source of truth): flat index of cell {@code (x, engineY, z)},
     *  {@code engineY in [0,CHUNK_H)}. Row bases use {@code colIdx(0, engineY, z)}. */
    public static int colIdx(int x, int engineY, int z) {
        return x + COL_Y_STRIDE * engineY + COL_Z_STRIDE * z;
    }

    /** Inverse of {@link #colIdx}: the cell x of a flat engine-column index (idx is non-negative). */
    public static int colX(int idx) { return idx % CHUNK_W; }

    /** Inverse of {@link #colIdx}: the engine Y in {@code [0,CHUNK_H)} of a flat engine-column index. */
    public static int colY(int idx) { return (idx / COL_Y_STRIDE) % CHUNK_H; }

    /** Inverse of {@link #colIdx}: the cell z of a flat engine-column index. */
    public static int colZ(int idx) { return idx / COL_Z_STRIDE; }

    /** {@code pxIn/pyIn/pzIn} = the marshalled EXTENSIVE momentum p [kg·m/s] (from {@link ColumnTask#momX()}
     *  etc.; law §7) — the conserved transported channel; {@code v=p/m} is derived at display only.
     *  {@code eIn} = the marshalled stored absolute-E [J] (from {@link ColumnTask#enthalpy()}), length
     *  nCols·CHUNK_N. NOTE: distinct from NativeEngine's reconstructed {@code eIn} scratch — this is the
     *  threaded stored channel S5 will switch the engine feed to use (law §6/§7). */
    public record Flat(int nCols, int[] cx, int[] cz,
                       char[] matIx, float[] mass, float[] tIn,
                       float[] pxIn, float[] pyIn, float[] pzIn, float[] pIn,
                       float[] swapReadyIn, float[] eIn) {}

    public static Flat flatten(List<ColumnTask> cols) {
        int n = cols.size();
        int[] cx = new int[n], cz = new int[n];
        char[] matIx = new char[n * CHUNK_N];
        float[] mass = new float[n * CHUNK_N];
        float[] tIn = new float[n * CHUNK_N];
        float[] pxIn = new float[n * CHUNK_N];
        float[] pyIn = new float[n * CHUNK_N];
        float[] pzIn = new float[n * CHUNK_N];
        float[] pIn = new float[n * CHUNK_N];
        float[] swapReadyIn = new float[n * CHUNK_N];
        float[] eIn = new float[n * CHUNK_N];
        for (int c = 0; c < n; c++) {
            ColumnTask t = cols.get(c);
            if (t.matIx().length != CHUNK_N || t.mass().length != CHUNK_N || t.temperature().length != CHUNK_N)
                throw new IllegalArgumentException("column arrays must be CHUNK_N=" + CHUNK_N);
            cx[c] = t.cx(); cz[c] = t.cz();
            int base = c * CHUNK_N;
            System.arraycopy(t.matIx(), 0, matIx, base, CHUNK_N);
            System.arraycopy(t.mass(), 0, mass, base, CHUNK_N);
            System.arraycopy(t.temperature(), 0, tIn, base, CHUNK_N);
            System.arraycopy(t.momX(), 0, pxIn, base, CHUNK_N);
            System.arraycopy(t.momY(), 0, pyIn, base, CHUNK_N);
            System.arraycopy(t.momZ(), 0, pzIn, base, CHUNK_N);
            System.arraycopy(t.p(), 0, pIn, base, CHUNK_N);
            System.arraycopy(t.swapReady(), 0, swapReadyIn, base, CHUNK_N);
            System.arraycopy(t.enthalpy(), 0, eIn, base, CHUNK_N);
        }
        return new Flat(n, cx, cz, matIx, mass, tIn, pxIn, pyIn, pzIn, pIn, swapReadyIn, eIn);
    }

    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] pxOut, float[] pyOut, float[] pzOut,
                                           float[] pOut, float[] swapReadyOut, float[] eOut, int nCols) {
        List<ColumnResult> out = new ArrayList<>(nCols);
        for (int c = 0; c < nCols; c++) {
            int base = c * CHUNK_N;
            char[] mi = new char[CHUNK_N];
            float[] ms = new float[CHUNK_N];
            float[] tt = new float[CHUNK_N];
            float[] pxs = new float[CHUNK_N];
            float[] pys = new float[CHUNK_N];
            float[] pzs = new float[CHUNK_N];
            float[] ps  = new float[CHUNK_N];
            float[] sr  = new float[CHUNK_N];
            float[] es  = new float[CHUNK_N];
            System.arraycopy(matOut,  base, mi,  0, CHUNK_N);
            System.arraycopy(massOut, base, ms,  0, CHUNK_N);
            System.arraycopy(tOut,    base, tt,  0, CHUNK_N);
            System.arraycopy(pxOut,   base, pxs, 0, CHUNK_N);
            System.arraycopy(pyOut,   base, pys, 0, CHUNK_N);
            System.arraycopy(pzOut,   base, pzs, 0, CHUNK_N);
            System.arraycopy(pOut,    base, ps,  0, CHUNK_N);
            System.arraycopy(swapReadyOut, base, sr, 0, CHUNK_N);
            System.arraycopy(eOut,    base, es,  0, CHUNK_N);
            out.add(new ColumnResult(mi, ms, tt, pxs, pys, pzs, ps, sr, es));
        }
        return out;
    }

    // ---- Convenience slice overloads: every one routes DIRECTLY through the canonical 10-arg slice
    // above, zero-filling exactly the trailing channels it omits. No tower (no overload delegates to
    // another overload) — the channel layout lives in one place. ----

    /** Convenience: enthalpy (absolute E [J]) channel zero-filled. swapReady supplied.
     *  Used by callers that don't thread eOut. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] pxOut, float[] pyOut, float[] pzOut,
                                           float[] pOut, float[] swapReadyOut, int nCols) {
        int total = nCols * CHUNK_N;
        return slice(matOut, massOut, tOut, pxOut, pyOut, pzOut, pOut, swapReadyOut,
                     new float[total], nCols);
    }

    /** Convenience: swapReady + enthalpy channels zero-filled. Momentum + pressure supplied. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] pxOut, float[] pyOut, float[] pzOut,
                                           float[] pOut, int nCols) {
        int total = nCols * CHUNK_N;
        return slice(matOut, massOut, tOut, pxOut, pyOut, pzOut, pOut,
                     new float[total], new float[total], nCols);
    }

    /** Convenience: pressure + swapReady + enthalpy channels zero-filled. Momentum supplied. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] pxOut, float[] pyOut, float[] pzOut, int nCols) {
        int total = nCols * CHUNK_N;
        return slice(matOut, massOut, tOut, pxOut, pyOut, pzOut,
                     new float[total], new float[total], new float[total], nCols);
    }

    /** Convenience: momentum + pressure + swapReady + enthalpy channels zero-filled. Used by tests that
     *  only care about mat/mass/T. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut, int nCols) {
        int total = nCols * CHUNK_N;
        float[] zeros = new float[total];
        return slice(matOut, massOut, tOut, zeros, zeros, zeros, zeros, zeros, new float[total], nCols);
    }

    private RegionMarshaller() {}
}
