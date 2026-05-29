package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * A no-op {@link OrgeEngine} for the Phase 1 skeleton: returns each section's input
 * temperatures unchanged. Lets the scheduler and section-store plumbing be built and
 * tested before the native liborge FFI binding lands (DESIGN.md §2).
 */
public final class StubEngine implements OrgeEngine {

    private double lastStepMillis = 0.0;

    @Override
    public List<float[]> step(List<StepTask> tasks, List<Material> lut, double dtSeconds) {
        List<float[]> out = new ArrayList<>(tasks.size());
        for (StepTask task : tasks) {
            // Identity step: copy temperatures through untouched.
            out.add(task.temperature().clone());
        }
        lastStepMillis = 0.0;
        return out;
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
