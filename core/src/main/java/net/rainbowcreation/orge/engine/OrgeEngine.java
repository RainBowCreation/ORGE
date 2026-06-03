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
     * Register the resident material table for {@code lutEpoch}. Called on every publish (initial
     * datapack load + each {@code /reload}). Later {@link #stepWorld} calls select it by epoch. The
     * {@link StubEngine} no-ops (records matCount only). Unknown epoch at step time is a safe no-op.
     */
    void registerMaterials(int lutEpoch, java.util.List<net.rainbowcreation.orge.material.Material> table);

    /** Step the joined active region; material physics is supplied out-of-band by {@link #registerMaterials}
     *  and selected by {@code lutEpoch}. Unknown epoch ⇒ safe no-op (inputs pass through). */
    java.util.List<ColumnResult> stepWorld(java.util.List<ColumnTask> columns,
                                           int lutEpoch, double dtSeconds, int passes);

    /** Injection-aware step; default delegates to the 4-arg form with an empty ledger. */
    default RegionStepResult stepWorld(java.util.List<ColumnTask> columns,
                                       int lutEpoch, double dtSeconds, int passes,
                                       java.util.List<EngineInjection> injections) {
        java.util.List<ColumnResult> cols = stepWorld(columns, lutEpoch, dtSeconds, passes);
        return new RegionStepResult(cols, new float[0], new float[0]);
    }

    /** Per-section compute time of the last {@link #step}, ms — drives health throttling. */
    double lastStepMillis();
}
