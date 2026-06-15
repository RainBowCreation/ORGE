package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StubEngine implements OrgeEngine {

    private double lastStepMillis = 0.0;
    private final Map<Integer, Integer> epochMatCount = new ConcurrentHashMap<>();

    @Override
    public void registerMaterials(int lutEpoch, List<Material> table) {
        epochMatCount.put(lutEpoch, table.size());
    }

    @Override
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, int lutEpoch,
                                        double dtSeconds, int passes) {
        List<ColumnResult> out = new ArrayList<>(columns.size());
        for (ColumnTask c : columns) {
            out.add(new ColumnResult(c.matIx().clone(), c.mass().clone(), c.temperature().clone()));
        }
        lastStepMillis = 0.0;
        return out;
    }

    @Override
    public RegionStepResult stepWorld(List<ColumnTask> columns, int lutEpoch,
                                      double dtSeconds, int passes, List<EngineInjection> injections) {
        int n = epochMatCount.getOrDefault(lutEpoch, 0);
        // No injection in the stub: mass + energy ledger sides are all-zero (T10.8 grew the result with
        // injectedE/sealedE).
        return new RegionStepResult(stepWorld(columns, lutEpoch, dtSeconds, passes),
                new float[n], new float[n], new float[n], new float[n]);
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
