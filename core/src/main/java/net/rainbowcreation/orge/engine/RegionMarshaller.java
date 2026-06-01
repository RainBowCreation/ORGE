package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import java.util.ArrayList;
import java.util.List;

/** Packs ColumnTasks into the flat arrays orgeStepWorld expects and slices results back.
 *  Column arrays are CHUNK_N long in engine order (idx = x + 16*y + 6144*z). */
public final class RegionMarshaller {
    public static final int CHUNK_W = 16, CHUNK_H = 384, CHUNK_D = 16;
    public static final int CHUNK_N = CHUNK_W * CHUNK_H * CHUNK_D; // 98304

    public record Flat(int nCols, int[] cx, int[] cz,
                       char[] matIx, float[] mass, float[] tIn,
                       LutArrays lut) {}

    public static Flat flatten(List<ColumnTask> cols, List<Material> lut) {
        int n = cols.size();
        int[] cx = new int[n], cz = new int[n];
        char[] matIx = new char[n * CHUNK_N];
        float[] mass = new float[n * CHUNK_N];
        float[] tIn = new float[n * CHUNK_N];
        for (int c = 0; c < n; c++) {
            ColumnTask t = cols.get(c);
            if (t.matIx().length != CHUNK_N || t.mass().length != CHUNK_N || t.temperature().length != CHUNK_N)
                throw new IllegalArgumentException("column arrays must be CHUNK_N=" + CHUNK_N);
            cx[c] = t.cx(); cz[c] = t.cz();
            int base = c * CHUNK_N;
            System.arraycopy(t.matIx(), 0, matIx, base, CHUNK_N);
            System.arraycopy(t.mass(), 0, mass, base, CHUNK_N);
            System.arraycopy(t.temperature(), 0, tIn, base, CHUNK_N);
        }
        return new Flat(n, cx, cz, matIx, mass, tIn, LutArrays.pack(lut));
    }

    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut, int nCols) {
        List<ColumnResult> out = new ArrayList<>(nCols);
        for (int c = 0; c < nCols; c++) {
            int base = c * CHUNK_N;
            char[] mi = new char[CHUNK_N];
            float[] ms = new float[CHUNK_N];
            float[] tt = new float[CHUNK_N];
            System.arraycopy(matOut, base, mi, 0, CHUNK_N);
            System.arraycopy(massOut, base, ms, 0, CHUNK_N);
            System.arraycopy(tOut, base, tt, 0, CHUNK_N);
            out.add(new ColumnResult(mi, ms, tt));
        }
        return out;
    }

    private RegionMarshaller() {}
}
