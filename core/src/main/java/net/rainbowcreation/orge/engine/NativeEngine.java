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
            float[] lutCond, float[] lutHeatCap, float[] lutVisc,
            float[] lutFullMass, byte[] lutFluid,
            float[] lutMinFlow, float[] lutMaxMass, byte[] lutGas, byte[] lutAir,
            float[] lutMolar,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut, char[] matOut);

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
                f.lutCond(), f.lutHeatCap(), f.lutVisc(), f.lutFullMass(), f.lutFluid(),
                f.lutMinFlow(), f.lutMaxMass(), f.lutGas(), f.lutAir(),
                f.lutMolar(),
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
