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
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                                        double dtSeconds, int passes) {
        List<ColumnResult> out = new ArrayList<>(columns.size());
        for (ColumnTask c : columns) {
            out.add(new ColumnResult(c.matIx().clone(), c.mass().clone(), c.temperature().clone()));
        }
        lastStepMillis = 0.0;
        return out;
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
