package net.rainbowcreation.orge.engine;

import java.util.ArrayList;
import java.util.List;

/** Packs ColumnTasks into the flat arrays orgeStepWorld expects and slices results back.
 *  Column arrays are CHUNK_N long in engine order (idx = x + 16*y + 6144*z). */
public final class RegionMarshaller {
    public static final int CHUNK_W = 16, CHUNK_H = 384, CHUNK_D = 16;
    public static final int CHUNK_N = CHUNK_W * CHUNK_H * CHUNK_D; // 98304

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

    /** Back-compat overload: enthalpy (absolute E [J]) channel zero-filled. swapReady supplied.
     *  Used by callers that don't yet thread eOut (e.g. NativeEngine until S6). */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] pxOut, float[] pyOut, float[] pzOut,
                                           float[] pOut, float[] swapReadyOut, int nCols) {
        int total = nCols * CHUNK_N;
        return slice(matOut, massOut, tOut, pxOut, pyOut, pzOut, pOut, swapReadyOut, new float[total], nCols);
    }

    /** Back-compat overload: swapReady + enthalpy channels zero-filled. Momentum + pressure supplied. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] pxOut, float[] pyOut, float[] pzOut,
                                           float[] pOut, int nCols) {
        int total = nCols * CHUNK_N;
        return slice(matOut, massOut, tOut, pxOut, pyOut, pzOut, pOut, new float[total], nCols);
    }

    /** Back-compat overload: pressure + swapReady channels zero-filled. Momentum supplied. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] pxOut, float[] pyOut, float[] pzOut, int nCols) {
        int total = nCols * CHUNK_N;
        return slice(matOut, massOut, tOut, pxOut, pyOut, pzOut, new float[total], new float[total], nCols);
    }

    /** Back-compat overload: momentum + pressure + swapReady channels zero-filled. Used by tests that don't care. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut, int nCols) {
        int total = nCols * CHUNK_N;
        float[] zeros = new float[total];
        return slice(matOut, massOut, tOut, zeros, zeros, zeros, zeros, zeros, nCols);
    }

    private RegionMarshaller() {}
}
