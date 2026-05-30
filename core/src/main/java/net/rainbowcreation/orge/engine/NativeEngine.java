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

    /**
     * One conduction step over a flattened batch. All arrays are flat (see
     * {@link BatchMarshaller}); {@code tOut} (length n·4096) receives the new
     * temperatures. Returns the native compute time in milliseconds.
     */
    private static native double orgeStep(
            int n,
            char[] matIx, float[] mass, float[] tIn,
            float[] haloT, char[] haloMat,
            float[] lutCond, float[] lutHeatCap,
            double dtSeconds,
            float[] tOut);

    @Override
    public List<float[]> step(List<StepTask> tasks, List<Material> lut, double dtSeconds) {
        if (tasks.isEmpty()) {
            lastStepMillis = 0.0;
            return new ArrayList<>();
        }
        BatchMarshaller.Flat f = BatchMarshaller.flatten(tasks, lut);
        float[] tOut = new float[f.n() * BatchMarshaller.SEC_N];
        lastStepMillis = orgeStep(
                f.n(), f.matIx(), f.mass(), f.tIn(),
                f.haloT(), f.haloMat(), f.lutCond(), f.lutHeatCap(),
                dtSeconds, tOut);
        return BatchMarshaller.slice(tOut, f.n());
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
