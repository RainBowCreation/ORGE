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

    @Override
    public void registerMaterials(int lutEpoch, List<Material> table) {
        if (table.isEmpty()) return;
        LutArrays L = LutArrays.pack(table);
        // The heatCap LUT column still flows to the engine here (its own enthalpy curve §8.1 consumes it);
        // T2 S5 deletes ONLY the Java-side cp·T reconstruction cache, not this registration column.
        orgeRegisterMaterials(lutEpoch, L.matCount(),
                L.cond(), L.heatCap(), L.molar(), L.minMass(), L.maxMass(), L.visc(),
                L.defaultMass(), L.yieldStress(),
                L.minTemp(), L.maxTemp(), L.minTarget(), L.maxTarget(),
                L.emissivity(), L.thermalExpansion(),
                L.latentHeatMin(), L.latentHeatMax(), L.tRefGas());
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

        // F2 momentum ABI (law §7): the momentum slots carry EXTENSIVE momentum p [kg·m/s] directly,
        // sourced straight from Flat.pxIn()/pyIn()/pzIn() (= ColumnTask.momX/momY/momZ). No v→p
        // reconstruction here and none in the JNI seam — the engine loads p absolutely (mirror of the
        // absolute-E channel below). The local var names stay vxIn/vyIn/vzIn only to match the ABI-stable
        // positional slots of orgeStepWorld; their CONTENT is momentum, not velocity.
        float[] vxIn  = f.pxIn();
        float[] vyIn  = f.pyIn();
        float[] vzIn  = f.pzIn();
        float[] pIn   = f.pIn();
        // OUT slots also carry EXTENSIVE momentum p [kg·m/s] (the JNI writes C->px/py/pz directly, no p/m).
        // These thread into RegionMarshaller.slice's momentum positions → ColumnResult.momX/momY/momZ.
        float[] vxOut = scratch.velXOut(total);
        float[] vyOut = scratch.velYOut(total);
        float[] vzOut = scratch.velZOut(total);
        float[] pOut  = scratch.pOut(total);

        // T2 S5 absolute-E feed (law §6 — E is THE energy truth carrier, T is a derived diagnostic).
        // eIn now flows straight from the S4-threaded stored-E channel (ColumnTask.enthalpy →
        // RegionMarshaller.Flat.eIn), exactly like vxIn = f.vxIn() and swapReadyIn = f.swapReadyIn():
        // the engine receives the STORED ABSOLUTE E [J] unmodified — loss-free across the JNI seam.
        // This DELETES the old mass·cp·T reconstruction, a single-slope linearisation that LOST energy
        // across a latent-heat plateau (a boiling cell pinned at 373 K absorbing ~2.256 MJ/kg carries E
        // far above m·cp·373; cp·T would silently collapse it onto a band edge). The engine treats E as
        // truth and reconstructs nothing (orge_jni.cpp: "C->E[i] = eIn[i]; NEVER reconstruct E from T").
        float[] eIn = f.eIn();
        // swapReadyIn: sourced from the persisted Java channel (ColumnTask.swapReady, marshalled into
        // Flat.swapReadyIn) — taken straight from Flat, exactly like vxIn = f.vxIn(). The engine reads it
        // directly, round-trips it, and resets-on-mismatch per v4 §1.1. Java now PERSISTS this channel
        // across stepWorld calls (T10c) so the §5.3 seconds-floor swap cadence can accumulate toward its
        // ≥1 fire threshold instead of being re-zeroed each tick.
        float[] swapReadyIn = f.swapReadyIn();
        // Eout: the engine's AUTHORITATIVE post-step absolute E [J] (law §6). T2 S5 threads it back through
        // the 10-arg slice into ColumnResult.enthalpy so S6 can write the stored-E channel back to the world
        // (the T channel remains the derived-Kelvin diagnostic). swapReadyOut: persisted §5.3 accumulator.
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
                RegionMarshaller.slice(matOut, massOut, tOut, vxOut, vyOut, vzOut, pOut, swapReadyOut, eOut, f.nCols()),
                injected, sealedLoss, injectedE, sealedE);
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }

}
