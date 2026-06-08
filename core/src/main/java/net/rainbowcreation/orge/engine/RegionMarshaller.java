package net.rainbowcreation.orge.engine;

import java.util.ArrayList;
import java.util.List;

/** Packs ColumnTasks into the flat arrays orgeStepWorld expects and slices results back.
 *  Column arrays are CHUNK_N long in engine order (idx = x + 16*y + 6144*z). */
public final class RegionMarshaller {
    public static final int CHUNK_W = 16, CHUNK_H = 384, CHUNK_D = 16;
    public static final int CHUNK_N = CHUNK_W * CHUNK_H * CHUNK_D; // 98304

    public record Flat(int nCols, int[] cx, int[] cz,
                       char[] matIx, float[] mass, float[] tIn,
                       float[] vxIn, float[] vyIn, float[] vzIn, float[] pIn) {}

    public static Flat flatten(List<ColumnTask> cols) {
        int n = cols.size();
        int[] cx = new int[n], cz = new int[n];
        char[] matIx = new char[n * CHUNK_N];
        float[] mass = new float[n * CHUNK_N];
        float[] tIn = new float[n * CHUNK_N];
        float[] vxIn = new float[n * CHUNK_N];
        float[] vyIn = new float[n * CHUNK_N];
        float[] vzIn = new float[n * CHUNK_N];
        float[] pIn = new float[n * CHUNK_N];
        for (int c = 0; c < n; c++) {
            ColumnTask t = cols.get(c);
            if (t.matIx().length != CHUNK_N || t.mass().length != CHUNK_N || t.temperature().length != CHUNK_N)
                throw new IllegalArgumentException("column arrays must be CHUNK_N=" + CHUNK_N);
            cx[c] = t.cx(); cz[c] = t.cz();
            int base = c * CHUNK_N;
            System.arraycopy(t.matIx(), 0, matIx, base, CHUNK_N);
            System.arraycopy(t.mass(), 0, mass, base, CHUNK_N);
            System.arraycopy(t.temperature(), 0, tIn, base, CHUNK_N);
            System.arraycopy(t.velX(), 0, vxIn, base, CHUNK_N);
            System.arraycopy(t.velY(), 0, vyIn, base, CHUNK_N);
            System.arraycopy(t.velZ(), 0, vzIn, base, CHUNK_N);
            System.arraycopy(t.p(), 0, pIn, base, CHUNK_N);
        }
        return new Flat(n, cx, cz, matIx, mass, tIn, vxIn, vyIn, vzIn, pIn);
    }

    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] vxOut, float[] vyOut, float[] vzOut,
                                           float[] pOut, int nCols) {
        List<ColumnResult> out = new ArrayList<>(nCols);
        for (int c = 0; c < nCols; c++) {
            int base = c * CHUNK_N;
            char[] mi = new char[CHUNK_N];
            float[] ms = new float[CHUNK_N];
            float[] tt = new float[CHUNK_N];
            float[] vxs = new float[CHUNK_N];
            float[] vys = new float[CHUNK_N];
            float[] vzs = new float[CHUNK_N];
            float[] ps  = new float[CHUNK_N];
            System.arraycopy(matOut,  base, mi,  0, CHUNK_N);
            System.arraycopy(massOut, base, ms,  0, CHUNK_N);
            System.arraycopy(tOut,    base, tt,  0, CHUNK_N);
            System.arraycopy(vxOut,   base, vxs, 0, CHUNK_N);
            System.arraycopy(vyOut,   base, vys, 0, CHUNK_N);
            System.arraycopy(vzOut,   base, vzs, 0, CHUNK_N);
            System.arraycopy(pOut,    base, ps,  0, CHUNK_N);
            out.add(new ColumnResult(mi, ms, tt, vxs, vys, vzs, ps));
        }
        return out;
    }

    /** Back-compat overload: pressure channel zero-filled. Velocity supplied. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut,
                                           float[] vxOut, float[] vyOut, float[] vzOut, int nCols) {
        int total = nCols * CHUNK_N;
        return slice(matOut, massOut, tOut, vxOut, vyOut, vzOut, new float[total], nCols);
    }

    /** Back-compat overload: velocity + pressure channels zero-filled. Used by tests that don't care. */
    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut, int nCols) {
        int total = nCols * CHUNK_N;
        float[] zeros = new float[total];
        return slice(matOut, massOut, tOut, zeros, zeros, zeros, zeros, nCols);
    }

    private RegionMarshaller() {}
}
