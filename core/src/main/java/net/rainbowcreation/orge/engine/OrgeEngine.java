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

    /**
     * Run one conduction step over the batch.
     *
     * @param tasks    sections to step, each with geometry + temperatures + halo
     * @param lut      material table, indexed by {@link StepTask#matIx()} values
     * @param dtSeconds time step (DESIGN.md §4: 1.0 s)
     * @return new temperatures per section, in the same order as {@code tasks}
     */
    List<float[]> step(List<StepTask> tasks, List<Material> lut, double dtSeconds);

    /** Per-section compute time of the last {@link #step}, ms — drives health throttling. */
    double lastStepMillis();
}
