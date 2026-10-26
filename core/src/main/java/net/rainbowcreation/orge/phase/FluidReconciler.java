package net.rainbowcreation.orge.phase;

import java.util.List;
import java.util.function.IntFunction;

import net.minecraft.world.level.block.state.BlockState;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
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

    /** The server-thread half of a reconcile planned off-thread (issue #51). */
    @FunctionalInterface
    interface Plan {
        /**
         * Paint the planned blocks. {@code changedSinceCapture}: a block of the section changed after the
         * capture the plan was made from, so the plan is stale there and the section is reconciled live
         * instead. Server thread only.
         */
        void apply(boolean changedSinceCapture);
    }

    /**
     * Off-thread half of {@link #reconcile(ThermalWorld.BatchEntry, char[], List)} (issue #51): decide the
     * section's block writes from {@code captured} — the section's block states as this cycle's snapshot
     * captured them — and {@code after}, the section the write-back installs. Must not touch live world or
     * store state. The default plans nothing and reconciles live at apply time.
     */
    default Plan plan(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut,
                      SectionData after, IntFunction<BlockState> captured) {
        return changedSinceCapture -> reconcile(entry, outMaterial, outLut);
    }

    FluidReconciler NOOP = entry -> {};
}
