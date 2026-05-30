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
}
