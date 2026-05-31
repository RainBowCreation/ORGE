package net.rainbowcreation.orge.phase;

import java.util.List;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.ThermalWorld;

/**
 * The §10 reconcile seam the {@link net.rainbowcreation.orge.scheduler.Scheduler} calls once per
 * written section, after write-back and beside {@link PhaseChanger}: map each cell's stored mass
 * to a {@code minecraft:water}/{@code lava} render level — place a block on mass gain, remove on
 * mass ≈ 0. Keeps the scheduler loader-free; the live impl is {@code MinecraftFluidReconciler}.
 */
public interface FluidReconciler {
    void reconcile(ThermalWorld.BatchEntry entry);

    /**
     * Reconcile a section's mass to vanilla render levels (DESIGN §10). {@code outMaterial} is the
     * engine's per-cell output species ({@code StepResult.material()}, the {@code matOut} array) — the
     * species each cell BECAME this step, used to know which block to place when wetting an air cell;
     * {@code outLut} is the step's batch material table that resolves those indices. When either is
     * null the reconciler falls back to the world block's material.
     */
    default void reconcile(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut) {
        reconcile(entry); // back-compat: NOOP and the world-block-only path
    }

    FluidReconciler NOOP = entry -> {};
}
