package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;

/**
 * The scheduler's window onto the world (the only seam it touches for I/O). The pure
 * orchestrator stays testable behind it; the live implementation ({@code MinecraftThermalWorld})
 * reads chunks/players and writes to the {@link net.rainbowcreation.orge.section.SectionStore}.
 */
public interface ThermalWorld {

    /** One section's task plus the dimension it belongs to (for write-back/halo routing). */
    record BatchEntry(Identifier dimension, SubchunkKey key, StepTask task) {}

    /** A step's worth of work: the dimension-tagged tasks + the shared material LUT. */
    record Batch(List<BatchEntry> entries, List<Material> lut) {}

    /**
     * Assemble this step's batch on the server thread: build the player-sphere (+ forced)
     * union at {@code range}, drop unloaded sections, and assemble each surviving section's
     * {@link StepTask} (geometry + a COPY of its temperatures + halo). Returns an empty batch
     * when there is nothing to simulate.
     */
    Batch snapshot(int range);

    /**
     * Persist one validated result on the server thread: write the result's temperatures AND mass
     * into the entry's section and mark its column dirty (§5). A section that has unloaded since the
     * snapshot is skipped.
     */
    void writeBack(BatchEntry entry, StepResult result);

    /**
     * Record a section's per-step settle deltas (DESIGN §10 Decision 11). Called from the writeback
     * loop with the max |Δmass| (advection) / max |ΔT| (conduction) over the section's 4096 cells, so
     * a quiet section counts down toward dormancy. {@code maxMassDelta < 0} means "no advection this
     * cycle" (skip the flow countdown); likewise {@code maxTempDelta < 0} skips thermal. Default no-op
     * for headless test worlds.
     */
    default void noteSettle(BatchEntry entry, float maxMassDelta, float maxTempDelta) { }

    /**
     * Wake the flow pass of a section adjacent to one that pushed mass across the shared seam
     * (DESIGN §10 Decision 11 trigger (c)). Without this an active section that pushes fluid toward a
     * dormant neighbour would see flow stop dead at the border (the dormant neighbour is never
     * snapshotted). Default no-op for headless test worlds.
     */
    default void wakeNeighbourFlow(Identifier dim, SubchunkKey neighbour) { }

    /**
     * Record the material each cell's just-persisted mass now belongs to (DESIGN §10 follow-on).
     * Called from the writeback loop AFTER a successful advection writeBack with the engine's output
     * species ({@code outMat}, may be null for stub/back-compat) and the batch {@code lut}. The live
     * impl updates its CellMaterialTracker so the NEXT snapshot's MaterialChangeReseed treats
     * engine-driven fluid placements (the reconciler turning a wetted air cell into water) as
     * already-known and reseeds only genuine external edits. Default no-op for headless test worlds.
     */
    default void recordCellMaterials(BatchEntry entry, char[] outMat, java.util.List<Material> lut) { }
}
