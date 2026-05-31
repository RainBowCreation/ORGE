package net.rainbowcreation.orge.phase;

import java.util.List;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.ThermalWorld;
import net.rainbowcreation.orge.section.SubchunkKey;

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

    /**
     * Re-render a section's stored mass to vanilla render levels addressed directly by
     * {@code (dim, key)} rather than a {@link ThermalWorld.BatchEntry} (DESIGN §10 cross-section
     * fall). The scheduler calls this AFTER the write-back loop for each
     * {@link ThermalWorld.TouchedSection} the seam pass mutated — including a RECEIVER section that
     * was never a batch entry this cycle. Same body as the {@code BatchEntry} overload (it reads the
     * §5 store, not the task); {@code species} is the post-transfer per-cell output species and
     * {@code outLut} resolves those indices. Default no-op for headless test worlds / NOOP.
     */
    default void reconcile(Identifier dim, SubchunkKey key, char[] species, List<Material> outLut) { }

    FluidReconciler NOOP = entry -> {};
}
