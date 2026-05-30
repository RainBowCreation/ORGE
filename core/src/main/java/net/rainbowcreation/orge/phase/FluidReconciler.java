package net.rainbowcreation.orge.phase;

import net.rainbowcreation.orge.scheduler.ThermalWorld;

/**
 * The §10 reconcile seam the {@link net.rainbowcreation.orge.scheduler.Scheduler} calls once per
 * written section, after write-back and beside {@link PhaseChanger}: map each cell's stored mass
 * to a {@code minecraft:water}/{@code lava} render level — place a block on mass gain, remove on
 * mass ≈ 0. Keeps the scheduler loader-free; the live impl is {@code MinecraftFluidReconciler}.
 */
public interface FluidReconciler {
    void reconcile(ThermalWorld.BatchEntry entry);
    FluidReconciler NOOP = entry -> {};
}
