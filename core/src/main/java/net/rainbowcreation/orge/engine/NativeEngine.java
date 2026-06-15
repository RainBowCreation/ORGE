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
     * <p><b>v4 §1.2 / law §8 17-column LUT (issue #2; {@code orge_jni.cpp} must match exactly):</b>
     * eight base physics floats — {@code cond} (thermal_conductivity), {@code heatCap}, {@code molar},
     * {@code minMass}, {@code maxMass}, {@code visc} (+∞ = frozen/immovable), {@code defaultMass}
     * (EOS rest density m₀), {@code yieldStress} (threshold axis, 0 for fluids) — the phase quadruple
     * {@code minTemp}/{@code maxTemp} and {@code minTarget}/{@code maxTarget} (the target material's
     * globally-stable {@code matIx}, {@code 0xFFFF} = no target) — and the five v4 §1.2 columns APPENDED
     * at the end: {@code emissivity} (ε, radiation §8.3), {@code thermalExpansion} (β, convection ρ_eff),
     * {@code latentHeatMin}/{@code latentHeatMax} (latent heat of the min/max transition, §8.1), and
     * {@code tRefGas} (per-gas EOS reference T, §2.1). The phase quadruple is engine-resident so a future
     * DECODE relabels locally. Immovability is {@code visc == +∞}, not a flag.</p>
     *
     * <p>Param order MUST match {@code orge_jni.cpp} (the 5 new arrays come last). Returns the native
     * compute time in milliseconds.</p>
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
            int[] minTarget, int[] maxTarget,
            float[] emissivity, float[] thermalExpansion,
            float[] latentHeatMin, float[] latentHeatMax, float[] tRefGas);

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
            float[] ledgerOut,
            // T10.8 ABI growth (engine a2a51cd): four per-cell payload arrays APPENDED at the very end
            // so every existing param keeps its position. Each length nCols·CHUNK_N (same shape as mass).
            //   eIn / eOut         : ABSOLUTE enthalpy E [J] (law #6 — E is the truth carrier; T crosses
            //                        only as a derived diagnostic). Loss-free across the JNI seam.
            //   swapReadyIn/Out    : §5.3 swap-cadence accumulator (law #7), round-tripped so the per-call
            //                        World rebuild does not reset it each tick.
            // ledgerOut above is now [4*matCount] = injected, sealedLoss, injectedE, sealedE; the C++
            // writes the E side ONLY when the array length >= 4*matCount (forward-compat guard).
            float[] eIn, float[] swapReadyIn,
            float[] eOut, float[] swapReadyOut);

    private final java.util.Map<Integer, Integer> epochMatCount = new java.util.concurrent.ConcurrentHashMap<>();
    // T10.8: per-epoch heat-capacity (cp) by matIx, used to reconstruct the absolute-E channel
    // (Ein = mass·cp·T) at the JNI boundary. This is the single-slope reconstruction the C++ USED to
    // do internally; Java now supplies it so the round-trip stays bit-identical to today for off-plateau
    // cells (true cross-tick absolute-E persistence is the Subtask 9 disk decision). Keyed like
    // epochMatCount so a stale/evicted epoch yields no cp array (Ein falls back to 0 = massless).
    private final java.util.Map<Integer, float[]> epochHeatCap = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void registerMaterials(int lutEpoch, List<Material> table) {
        if (table.isEmpty()) return;
        LutArrays L = LutArrays.pack(table);
        orgeRegisterMaterials(lutEpoch, L.matCount(),
                L.cond(), L.heatCap(), L.molar(), L.minMass(), L.maxMass(), L.visc(),
                L.defaultMass(), L.yieldStress(),
                L.minTemp(), L.maxTemp(), L.minTarget(), L.maxTarget(),
                L.emissivity(), L.thermalExpansion(),
                L.latentHeatMin(), L.latentHeatMax(), L.tRefGas());
        epochMatCount.put(lutEpoch, table.size());
        epochHeatCap.put(lutEpoch, L.heatCap());
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
            return new RegionStepResult(new ArrayList<>(),
                    new float[matCount], new float[matCount],
                    new float[matCount], new float[matCount]);
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
            // T10.8: ledger is now [4*matCount] = injected, sealedLoss, injectedE, sealedE. The C++
            // writes the E side (indices [2n..4n)) ONLY because we now pass it room (length >= 4*matCount).
            ledgerOut = new float[4 * matCount];
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

        // T10.8 absolute-E + swap-cadence channels (engine a2a51cd ABI).
        // Ein: reconstruct ABSOLUTE E from the temperature the disk/live world still persists,
        // Ein = mass·cp·T (the single-slope reconstruction the C++ used to do internally; Java now
        // supplies it). This keeps the round-trip bit-identical to today for off-plateau cells AND
        // loss-free within the tick for any cell the engine moves. Cross-tick mid-plateau persistence
        // is the Subtask 9 disk decision. cp comes from the per-epoch heatCap cache; a void / 0-cp
        // cell (or unknown epoch ⇒ null cp) yields Ein=0, matching the engine's massless fallback.
        float[] eIn = scratch.eIn(total);
        float[] cp = epochHeatCap.get(lutEpoch);
        char[] matIxFlat = f.matIx();
        float[] massFlat = f.mass();
        float[] tInFlat = f.tIn();
        if (cp != null) {
            for (int i = 0; i < total; i++) {
                int mi = matIxFlat[i];
                float c = (mi < cp.length) ? cp[mi] : 0f;
                eIn[i] = massFlat[i] * c * tInFlat[i];
            }
        } else {
            java.util.Arrays.fill(eIn, 0, total, 0f);
        }
        // swapReadyIn: seed to 0 (NOT persisted to disk; the engine round-trips it WITHIN the call and
        // re-establishes the cadence across calls per v4 §1.1 reset-on-mismatch). The scratch buffer may
        // be reused, so zero exactly the [0,total) window we hand the native.
        float[] swapReadyIn = scratch.swapReadyIn(total);
        java.util.Arrays.fill(swapReadyIn, 0, total, 0f);
        // Eout/swapReadyOut: captured into scratch. ColumnResult.temperature (T-derived) still carries the
        // thermal state for the live write-back, so Eout is not yet consumed downstream — that is fine for
        // this subtask (Subtask 9 wires absolute-E persistence). Captured to satisfy the loss-free seam.
        float[] eOut = scratch.eOut(total);
        float[] swapReadyOut = scratch.swapReadyOut(total);

        lastStepMillis = orgeStepWorld(
                lutEpoch, f.nCols(), f.cx(), f.cz(), f.matIx(), f.mass(), f.tIn(),
                vxIn, vyIn, vzIn, pIn,
                passes, dtSeconds, tOut, massOut, matOut,
                vxOut, vyOut, vzOut, pOut,
                injCount, injCol, injCell, injSp, injMs, injTp, ledgerOut,
                eIn, swapReadyIn, eOut, swapReadyOut);

        float[] injected = new float[matCount];
        float[] sealedLoss = new float[matCount];
        float[] injectedE = new float[matCount];
        float[] sealedE = new float[matCount];
        if (injCount > 0 && matCount > 0) {
            System.arraycopy(ledgerOut, 0, injected, 0, matCount);
            System.arraycopy(ledgerOut, matCount, sealedLoss, 0, matCount);
            // E side: indices [2n..4n) — injectedE, sealedE (the C++ wrote these because we sized the
            // ledger to 4*matCount above; law #9 boundary energy ledger).
            System.arraycopy(ledgerOut, 2 * matCount, injectedE, 0, matCount);
            System.arraycopy(ledgerOut, 3 * matCount, sealedE, 0, matCount);
        }
        return new RegionStepResult(
                RegionMarshaller.slice(matOut, massOut, tOut, vxOut, vyOut, vzOut, pOut, f.nCols()),
                injected, sealedLoss, injectedE, sealedE);
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }

}
