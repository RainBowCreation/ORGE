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

    static {
        NativeLoader.load();
    }

    /**
     * One step over a flattened batch. All arrays are flat (see {@link BatchMarshaller});
     * {@code tOut}/{@code massOut} (length n·4096 each) receive the new temperatures + mass.
     * The {@code passes} bitmask selects conduction/advection. Param order MUST match
     * {@code orge_jni.cpp}. Returns the native compute time in milliseconds.
     */
    private static native double orgeStep(
            int n,
            char[] matIx, float[] mass, float[] tIn,
            float[] haloT, char[] haloMat, float[] haloMass,
            float[] lutCond, float[] lutHeatCap, float[] lutVisc,
            float[] lutFullMass, byte[] lutFluid,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut);

    @Override
    public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dtSeconds, int passes) {
        if (tasks.isEmpty()) {
            lastStepMillis = 0.0;
            return new ArrayList<>();
        }
        BatchMarshaller.Flat f = BatchMarshaller.flatten(tasks, lut);
        float[] tOut = new float[f.n() * BatchMarshaller.SEC_N];
        float[] massOut = new float[f.n() * BatchMarshaller.SEC_N];
        lastStepMillis = orgeStep(
                f.n(), f.matIx(), f.mass(), f.tIn(),
                f.haloT(), f.haloMat(), f.haloMass(),
                f.lutCond(), f.lutHeatCap(), f.lutVisc(), f.lutFullMass(), f.lutFluid(),
                passes, dtSeconds, tOut, massOut);
        List<float[]> t = BatchMarshaller.slice(tOut, f.n());
        List<float[]> m = BatchMarshaller.sliceMass(massOut, f.n());
        List<StepResult> out = new ArrayList<>(f.n());
        for (int s = 0; s < f.n(); s++) out.add(new StepResult(t.get(s), m.get(s)));
        return out;
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
