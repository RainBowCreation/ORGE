package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.List;

/**
 * {@link OrgeEngine} backed by the native liborge engine, called in-process via
 * JNI (DESIGN.md §2; the spec records why JNI and not Panama). Stateless: one
 * {@link #orgeStepWorld} call per {@link #stepWorld} invocation over the whole region.
 */
public final class NativeEngine implements OrgeEngine {

    // The whole flatten → scratch-reuse → JNI → ledger-copy → slice assembly lives behind this one
    // module; NativeEngine only supplies the raw native call (orgeStepWorld) as the EngineMarshaller.Kernel.
    private final EngineMarshaller marshaller = new EngineMarshaller();

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
        // The entire flatten/scratch/ledger/slice assembly is inside EngineMarshaller; NativeEngine
        // contributes only the raw JNI call (orgeStepWorld) as the Kernel method reference.
        return marshaller.step(columns, lutEpoch, matCount, dtSeconds, passes,
                injections, NativeEngine::orgeStepWorld);
    }

    @Override
    public double lastStepMillis() {
        return marshaller.lastStepMillis();
    }

}
