package net.rainbowcreation.orge.engine;

/**
 * The thermal solver facade (DESIGN.md §2). The real implementation is liborge,
 * called in-process via Panama FFI ({@code java.lang.foreign}); that wiring is
 * deferred to a later phase. {@link StubEngine} is the no-op stand-in until then.
 *
 * <p>A stateless, per-task stepper: given a batch of sections + their halos + the
 * material LUT + dt, run <b>one</b> step and return new temperatures (and, in
 * Phase 2, mass) per section.</p>
 */
public interface OrgeEngine {

    /** Pass selector bits (mirror the kernel's PASS_CONDUCTION/PASS_ADVECTION). */
    int PASS_CONDUCTION = 1;
    int PASS_ADVECTION  = 2;

    /**
     * Step the joined active region as one engine {@code World}: each {@link ColumnTask} is a full-height
     * column ({@link RegionMarshaller#CHUNK_N} cells, engine index {@code x + 16*y + 6144*z}). Native
     * cross-column X/Z flow + contiguous Y. Returns next-state columns in the same order as {@code columns}.
     *
     * <p>This is the sole engine entry point: the dormant per-section {@code step(...)} path was removed
     * in the unified-fluid rebuild (the section/halo model is abandoned; physics is boundary-agnostic).</p>
     *
     * @param columns  full-height columns to step
     * @param lut      material table, indexed by the columns' matIx values
     * @param dtSeconds time step (DESIGN.md §4: 1.0 s for conduction; §10: 0.25 s for advection)
     * @param passes   pass bitmask ({@link #PASS_CONDUCTION} and/or {@link #PASS_ADVECTION})
     */
    java.util.List<ColumnResult> stepWorld(java.util.List<ColumnTask> columns,
                                           java.util.List<net.rainbowcreation.orge.material.Material> lut,
                                           double dtSeconds, int passes);

    /**
     * Injection-aware step (placement displace-and-inject, spec Part A). Applies {@code injections}
     * once before advection, then steps as usual. The default implementation IGNORES injections and
     * delegates to the 4-arg {@link #stepWorld}, returning a zero placement ledger — so engines that
     * do not support the native injection channel (stub, test fakes) keep working. {@link NativeEngine}
     * overrides this to marshal the injection arrays and read back the ledger.
     *
     * @param injections placements for this step ({@code columnId} = position in {@code columns});
     *                   an empty list ⇒ identical to the 4-arg form.
     */
    default RegionStepResult stepWorld(java.util.List<ColumnTask> columns,
                                       java.util.List<net.rainbowcreation.orge.material.Material> lut,
                                       double dtSeconds, int passes,
                                       java.util.List<EngineInjection> injections) {
        java.util.List<ColumnResult> cols = stepWorld(columns, lut, dtSeconds, passes);
        int n = lut.size();
        return new RegionStepResult(cols, new float[n], new float[n]);
    }

    /** Per-section compute time of the last {@link #step}, ms — drives health throttling. */
    double lastStepMillis();
}
