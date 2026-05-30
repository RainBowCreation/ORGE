package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens a batch of {@link StepTask}s into the contiguous primitive arrays the
 * native {@code orgeStep} expects, and slices the flat result back into per-section
 * arrays. Pure (no native dependency) so it is unit-testable on its own.
 *
 * <p>Layout (see plan ground rules): per section, {@code matIx/mass/temperature}
 * are length {@value #SEC_N} (x-fastest); halos are six {@value #FACE}-cell faces
 * in the order negX, posX, negY, posY, negZ, posZ.</p>
 */
final class BatchMarshaller {

    static final int SEC_N = 4096;
    static final int FACE = NeighborHalo.FACE_CELLS;
    static final int FACES = 6;

    private BatchMarshaller() {}

    /** Flat inputs for one {@code orgeStep} call. {@code matCount} = LUT size. */
    record Flat(int n, char[] matIx, float[] mass, float[] tIn,
                float[] haloT, char[] haloMat,
                float[] lutCond, float[] lutHeatCap, int matCount) {}

    static Flat flatten(List<StepTask> tasks, List<Material> lut) {
        int m = lut.size();
        if (m == 0) throw new IllegalArgumentException("material LUT is empty");

        int n = tasks.size();
        char[] matIx = new char[n * SEC_N];
        float[] mass = new float[n * SEC_N];
        float[] tIn = new float[n * SEC_N];
        float[] haloT = new float[n * FACES * FACE];
        char[] haloMat = new char[n * FACES * FACE];

        for (int s = 0; s < n; s++) {
            StepTask t = tasks.get(s);
            requireLen(t.matIx(), SEC_N, "matIx", s);
            requireLen(t.mass(), SEC_N, "mass", s);
            requireLen(t.temperature(), SEC_N, "temperature", s);

            int base = s * SEC_N;
            System.arraycopy(t.matIx(), 0, matIx, base, SEC_N);
            System.arraycopy(t.mass(), 0, mass, base, SEC_N);
            System.arraycopy(t.temperature(), 0, tIn, base, SEC_N);
            for (int c = 0; c < SEC_N; c++) requireMat(matIx[base + c], m, "matIx", s);

            float[][] tf = t.halo().tempFaces();
            char[][] mf = t.halo().matFaces();
            for (int f = 0; f < FACES; f++) {
                requireLen(tf[f], FACE, "halo temp face " + f, s);
                requireLen(mf[f], FACE, "halo mat face " + f, s);
                int off = (s * FACES + f) * FACE;
                System.arraycopy(tf[f], 0, haloT, off, FACE);
                System.arraycopy(mf[f], 0, haloMat, off, FACE);
                for (int c = 0; c < FACE; c++) requireMat(haloMat[off + c], m, "halo mat", s);
            }
        }

        float[] cond = new float[m];
        float[] heatCap = new float[m];
        for (int i = 0; i < m; i++) {
            Material mat = lut.get(i);
            cond[i] = mat.thermalConductivity();
            heatCap[i] = mat.heatCapacity();
        }
        return new Flat(n, matIx, mass, tIn, haloT, haloMat, cond, heatCap, m);
    }

    static List<float[]> slice(float[] tOut, int n) {
        List<float[]> out = new ArrayList<>(n);
        for (int s = 0; s < n; s++) {
            float[] sec = new float[SEC_N];
            System.arraycopy(tOut, s * SEC_N, sec, 0, SEC_N);
            out.add(sec);
        }
        return out;
    }

    private static void requireLen(Object arr, int len, String what, int section) {
        int actual = java.lang.reflect.Array.getLength(arr);
        if (actual != len)
            throw new IllegalArgumentException(
                    "section " + section + " " + what + " length " + actual + " != " + len);
    }

    private static void requireMat(char ix, int matCount, String what, int section) {
        if (ix >= matCount)
            throw new IllegalArgumentException(
                    "section " + section + " " + what + " index " + (int) ix + " >= LUT size " + matCount);
    }
}
