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

    // Empty injection channel until Plan 2 wires the placement queue. With injCount==0 the native
    // skips the whole injection block and never reads/writes these, so shared immutable empties
    // (incl. ledgerOut) avoid per-step hot-path garbage.
    private static final int[] EMPTY_INT = new int[0];
    private static final char[] EMPTY_CHAR = new char[0];
    private static final float[] EMPTY_FLOAT = new float[0];

    static {
        NativeLoader.load();
    }

    /**
     * Whole-region step: build a transient engine {@code World} from {@code nCols} full-height columns
     * (each {@link RegionMarshaller#CHUNK_N} cells), run conduction and/or advection per {@code passes},
     * and read next-state back into {@code tOut}/{@code massOut}/{@code matOut} (length {@code nCols·CHUNK_N}).
     *
     * <p><b>Law §8 fixed-schema LUT (issue #2; {@code orge_jni.cpp} must match exactly):</b> eight
     * physics floats — {@code cond} (thermal_conductivity), {@code heatCap}, {@code molar},
     * {@code minMass}, {@code maxMass}, {@code visc} (+∞ = frozen/immovable), {@code defaultMass}
     * (EOS rest density m₀), {@code yieldStress} (threshold axis, 0 for fluids) — plus the phase
     * quadruple {@code minTemp}/{@code maxTemp} and {@code minTarget}/{@code maxTarget} (the target
     * material's globally-stable {@code matIx}, {@code 0xFFFF} = no target). The phase quadruple is
     * engine-resident so a future DECODE relabels locally. Immovability is {@code visc == +∞}, not a flag.</p>
     *
     * <p>Param order MUST match {@code orge_jni.cpp}. Returns the native compute time in milliseconds.</p>
     *
     * <p>The trailing injection channel ({@code injCount}, the five {@code inj*} arrays, and
     * {@code ledgerOut}) is the placement displace-and-inject path. It is passed EMPTY
     * ({@code injCount==0}) until Plan 2 wires the real placement queue; {@code injCount==0} skips
     * the whole injection block in the native, so behavior is byte-identical to before this channel.</p>
     */
    private static native void orgeRegisterMaterials(
            int lutEpoch, int matCount,
            float[] cond, float[] heatCap, float[] molar,
            float[] minMass, float[] maxMass, float[] visc,
            float[] defaultMass, float[] yieldStress,
            float[] minTemp, float[] maxTemp,
            int[] minTarget, int[] maxTarget);

    private static native double orgeStepWorld(
            int lutEpoch,
            int nCols, int[] cx, int[] cz,
            char[] matIx, float[] mass, float[] tIn,
            float[] vxIn, float[] vyIn, float[] vzIn, float[] pIn,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut, char[] matOut,
            float[] vxOut, float[] vyOut, float[] vzOut, float[] pOut,
            int injCount,
            int[] injColumn, int[] injCell,
            char[] injSpecies, float[] injMass, float[] injTemp,
            float[] ledgerOut);

    private final java.util.Map<Integer, Integer> epochMatCount = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void registerMaterials(int lutEpoch, List<Material> table) {
        if (table.isEmpty()) return;
        LutArrays L = LutArrays.pack(table);
        orgeRegisterMaterials(lutEpoch, L.matCount(),
                L.cond(), L.heatCap(), L.molar(), L.minMass(), L.maxMass(), L.visc(),
                L.defaultMass(), L.yieldStress(),
                L.minTemp(), L.maxTemp(), L.minTarget(), L.maxTarget());
        epochMatCount.put(lutEpoch, table.size());
    }

    @Override
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, int lutEpoch,
                                        double dtSeconds, int passes) {
        return stepWorld(columns, lutEpoch, dtSeconds, passes, java.util.List.of()).columns();
    }

    @Override
    public RegionStepResult stepWorld(List<ColumnTask> columns, int lutEpoch,
                                      double dtSeconds, int passes,
                                      List<EngineInjection> injections) {
        int matCount = epochMatCount.getOrDefault(lutEpoch, 0);
        if (columns.isEmpty()) {
            lastStepMillis = 0.0;
            return new RegionStepResult(new ArrayList<>(), new float[matCount], new float[matCount]);
        }
        RegionMarshaller.Flat f = RegionMarshaller.flatten(columns);
        int total = f.nCols() * RegionMarshaller.CHUNK_N;
        float[] tOut = scratch.temp(total);
        float[] massOut = scratch.mass(total);
        char[] matOut = scratch.material(total);

        int injCount = injections.size();
        int[] injCol; int[] injCell; char[] injSp; float[] injMs; float[] injTp; float[] ledgerOut;
        if (injCount == 0) {
            injCol = EMPTY_INT; injCell = EMPTY_INT; injSp = EMPTY_CHAR;
            injMs = EMPTY_FLOAT; injTp = EMPTY_FLOAT; ledgerOut = EMPTY_FLOAT;
        } else {
            injCol = new int[injCount]; injCell = new int[injCount]; injSp = new char[injCount];
            injMs = new float[injCount]; injTp = new float[injCount];
            for (int k = 0; k < injCount; k++) {
                EngineInjection in = injections.get(k);
                injCol[k] = in.columnId(); injCell[k] = in.cellIndex();
                injSp[k] = in.species(); injMs[k] = in.mass(); injTp[k] = in.temperature();
            }
            ledgerOut = new float[2 * matCount];
        }

        // Velocity + dynamic-pressure in from flatten; out from pool.
        float[] vxIn  = f.vxIn();
        float[] vyIn  = f.vyIn();
        float[] vzIn  = f.vzIn();
        float[] pIn   = f.pIn();
        float[] vxOut = scratch.velXOut(total);
        float[] vyOut = scratch.velYOut(total);
        float[] vzOut = scratch.velZOut(total);
        float[] pOut  = scratch.pOut(total);

        lastStepMillis = orgeStepWorld(
                lutEpoch, f.nCols(), f.cx(), f.cz(), f.matIx(), f.mass(), f.tIn(),
                vxIn, vyIn, vzIn, pIn,
                passes, dtSeconds, tOut, massOut, matOut,
                vxOut, vyOut, vzOut, pOut,
                injCount, injCol, injCell, injSp, injMs, injTp, ledgerOut);

        float[] injected = new float[matCount];
        float[] sealedLoss = new float[matCount];
        if (injCount > 0 && matCount > 0) {
            System.arraycopy(ledgerOut, 0, injected, 0, matCount);
            System.arraycopy(ledgerOut, matCount, sealedLoss, 0, matCount);
        }
        return new RegionStepResult(
                RegionMarshaller.slice(matOut, massOut, tOut, vxOut, vyOut, vzOut, pOut, f.nCols()),
                injected, sealedLoss);
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }

}
