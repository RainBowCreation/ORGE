package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link OrgeEngine} backed by the native liborge engine, called in-process via
 * JNI (DESIGN.md §2; the spec records why JNI and not Panama). Stateless: one
 * {@link #orgeStepWorld} call per {@link #stepWorld} invocation over the whole region.
 */
public final class NativeEngine implements OrgeEngine {

    private double lastStepMillis = 0.0;
    private final ScratchPool scratch = new ScratchPool();

    static {
        NativeLoader.load();
    }

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
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
