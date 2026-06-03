package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
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
    default Batch snapshot(int range) {
        return new Batch(List.of(), List.of(MaterialLut.VOID));
    }

    /**
     * Persist one validated result on the server thread: write the result's temperatures AND mass
     * into the entry's section and mark its column dirty (§5). A section that has unloaded since the
     * snapshot is skipped.
     *
     * <p>DORMANT: the per-section path is superseded by the whole-region column path
     * ({@link #snapshotColumns}/{@link #writeBackColumn}); kept as a default so the surviving
     * per-section test fakes still compile.</p>
     */
    default void writeBack(BatchEntry entry, StepResult result) { }

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

    // ----------------------------------------------------------------------------------------
    // Whole-region column path (DESIGN 2026-06-01 §5–§7). Replaces the per-section snapshot/
    // writeBack + halo/seam machinery: the active+apron column set is stepped as one engine World.
    // ----------------------------------------------------------------------------------------

    /** One full-height column ({@link ColumnTask}, {@link net.rainbowcreation.orge.engine.RegionMarshaller#CHUNK_N}
     *  cells) plus the key it came from for write-back. */
    record ColumnEntry(Identifier dimension, int cx, int cz, ColumnTask task) {}

    /** A cycle's worth of column work: the dimension-tagged columns + the shared material LUT, plus
     *  this cycle's drained placement injections (and the intents they came from, for clear-on-success). */
    record ColumnBatch(List<ColumnEntry> entries, List<Material> lut,
                       List<net.rainbowcreation.orge.engine.EngineInjection> injections,
                       List<PendingInjections.Intent> drained) {
        /** Back-compat convenience for call sites/tests that build a batch with no injections. */
        public ColumnBatch(List<ColumnEntry> entries, List<Material> lut) {
            this(entries, lut, List.of(), List.of());
        }
    }

    /**
     * Assemble this cycle's active+apron column set (the player-sphere union at {@code range}
     * projected to columns, expanded by one ring of LOADED neighbour columns — the apron;
     * MC-unloaded neighbours are excluded so they read as an absent-column wall). Each entry is a
     * full-height {@link ColumnTask} assembled via {@link ColumnAssembler}. Returns an empty batch
     * when there is nothing to simulate. Default empty for headless test worlds that only drive the
     * per-section path.
     */
    default ColumnBatch snapshotColumns(int range) {
        return new ColumnBatch(List.of(), List.of(MaterialLut.VOID));
    }

    /**
     * Persist one validated column result (T + mass) back into the 24 sections of {@code (cx,cz)},
     * reconcile MC blocks to the engine output species, record the output materials as the next
     * cycle's signature, settle, and wake any neighbour column that gained mass across an X/Z
     * boundary. Default no-op for headless test worlds.
     */
    default void writeBackColumn(ColumnEntry entry, ColumnResult result) { }
}
