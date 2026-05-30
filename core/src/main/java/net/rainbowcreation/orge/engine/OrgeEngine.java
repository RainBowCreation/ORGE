package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.List;

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
     * Run one step over the batch, selecting which physics passes to apply.
     *
     * @param tasks    sections to step, each with geometry + temperatures + mass + halo
     * @param lut      material table, indexed by {@link StepTask#matIx()} values
     * @param dtSeconds time step (DESIGN.md §4: 1.0 s for conduction; §10: 0.25 s for advection)
     * @param passes   pass bitmask ({@link #PASS_CONDUCTION} and/or {@link #PASS_ADVECTION})
     * @return new temperature + mass per section, in the same order as {@code tasks}
     */
    List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dtSeconds, int passes);

    /** Per-section compute time of the last {@link #step}, ms — drives health throttling. */
    double lastStepMillis();
}
