package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link OrgeEngine} backed by the native liborge conduction kernel, called
 * in-process via JNI (DESIGN.md §2; the spec records why JNI and not Panama).
 * Stateless and batched: one {@link #orgeStep} call per {@link #step} invocation.
 */
public final class NativeEngine implements OrgeEngine {

    private double lastStepMillis = 0.0;
    private final ScratchPool scratch = new ScratchPool();

    static {
        NativeLoader.load();
    }

    /**
     * One step over a flattened batch. All arrays are flat (see {@link BatchMarshaller});
     * {@code tOut}/{@code massOut}/{@code matOut} (length n·4096 each) receive the new
     * temperatures, mass, and material indices. The {@code passes} bitmask selects
     * conduction/advection. Param order MUST match {@code orge_jni.cpp}.
     * Returns the native compute time in milliseconds.
     */
    private static native double orgeStep(
            int n,
            char[] matIx, float[] mass, float[] tIn,
            float[] haloT, char[] haloMat, float[] haloMass,
            float[] lutCond, float[] lutHeatCap, float[] lutMolar,
            float[] lutMinMass, float[] lutMaxMass, float[] lutVisc,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut, char[] matOut);

    /**
     * Whole-region step: build a transient engine {@code World} from {@code nCols} full-height columns
     * (each {@link RegionMarshaller#CHUNK_N} cells), run conduction and/or advection per {@code passes},
     * and read next-state back into {@code tOut}/{@code massOut}/{@code matOut} (length {@code nCols·CHUNK_N}).
     *
     * <p><b>Canonical six-array LUT order (Task 2.1; Phase 3 C++ must match exactly):</b>
     * {@code lutCond} (thermal_conductivity), {@code lutHeatCap} (heat_capacity), {@code lutMolar}
     * (molar_mass), {@code lutMinMass} (min_mass), {@code lutMaxMass} (max_mass), {@code lutVisc}
     * (viscosity; +∞ = frozen/immovable). The legacy {@code lutFullMass/lutFluid/lutMinFlow/lutGas/
     * lutAir} arrays are gone — immovability is {@code visc == +∞}, not a flag.</p>
     *
     * <p>Param order MUST match {@code orge_jni.cpp}. Returns the native compute time in milliseconds.</p>
     */
    private static native double orgeStepWorld(
            int nCols, int[] cx, int[] cz,
            char[] matIx, float[] mass, float[] tIn,
            float[] lutCond, float[] lutHeatCap, float[] lutMolar,
            float[] lutMinMass, float[] lutMaxMass, float[] lutVisc,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut, char[] matOut);

    @Override
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                                        double dtSeconds, int passes) {
        if (columns.isEmpty()) { lastStepMillis = 0.0; return new ArrayList<>(); }
        RegionMarshaller.Flat f = RegionMarshaller.flatten(columns, lut);
        int total = f.nCols() * RegionMarshaller.CHUNK_N;
        float[] tOut = scratch.temp(total);
        float[] massOut = scratch.mass(total);
        char[] matOut = scratch.material(total);
        LutArrays L = f.lut();
        lastStepMillis = orgeStepWorld(
                f.nCols(), f.cx(), f.cz(), f.matIx(), f.mass(), f.tIn(),
                L.cond(), L.heatCap(), L.molar(), L.minMass(), L.maxMass(), L.visc(),
                passes, dtSeconds, tOut, massOut, matOut);
        return RegionMarshaller.slice(matOut, massOut, tOut, f.nCols());
    }

    @Override
    public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dtSeconds, int passes) {
        if (tasks.isEmpty()) {
            lastStepMillis = 0.0;
            return new ArrayList<>();
        }
        BatchMarshaller.Flat f = BatchMarshaller.flatten(tasks, lut);
        int total = f.n() * BatchMarshaller.SEC_N;
        float[] tOut    = scratch.temp(total);
        float[] massOut = scratch.mass(total);
        char[]  matOut  = scratch.material(total);
        lastStepMillis = orgeStep(
                f.n(), f.matIx(), f.mass(), f.tIn(),
                f.haloT(), f.haloMat(), f.haloMass(),
                f.lutCond(), f.lutHeatCap(), f.lutMolar(),
                f.lutMinMass(), f.lutMaxMass(), f.lutVisc(),
                passes, dtSeconds, tOut, massOut, matOut);
        List<float[]> t = BatchMarshaller.slice(tOut, f.n());
        List<float[]> m = BatchMarshaller.sliceMass(massOut, f.n());
        List<char[]>  mat = sliceMat(matOut, f.n());
        List<StepResult> out = new ArrayList<>(f.n());
        for (int s = 0; s < f.n(); s++) out.add(new StepResult(t.get(s), m.get(s), mat.get(s)));
        return out;
    }

    /** Splits a flat per-cell material output (n·SEC_N) back into per-section arrays. */
    private static List<char[]> sliceMat(char[] matOut, int n) {
        List<char[]> out = new ArrayList<>(n);
        for (int s = 0; s < n; s++) {
            char[] sec = new char[BatchMarshaller.SEC_N];
            System.arraycopy(matOut, s * BatchMarshaller.SEC_N, sec, 0, BatchMarshaller.SEC_N);
            out.add(sec);
        }
        return out;
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
