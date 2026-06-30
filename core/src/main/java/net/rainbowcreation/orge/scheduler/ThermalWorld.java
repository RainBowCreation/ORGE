package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
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

    /**
     * One section's task plus the dimension it belongs to. The whole-region column write-back
     * reconstructs one of these per section to drive the §10/§7 settle/reconcile/phase seams
     * ({@link #noteSettle}, {@link net.rainbowcreation.orge.phase.FluidReconciler#reconcile},
     * {@link net.rainbowcreation.orge.phase.PhaseChanger#applyPhaseChanges}).
     */
    record BatchEntry(Identifier dimension, SubchunkKey key, StepTask task) {}

    /**
     * Record a section's per-step settle deltas (DESIGN §10 Decision 11). Driven from the column
     * write-back per reconstructed section with the max |Δmass| (advection) / max |ΔT| (conduction)
     * over the section's 4096 cells, so a quiet section counts down toward dormancy.
     * {@code maxMassDelta < 0} means "no advection this cycle" (skip the flow countdown); likewise
     * {@code maxTempDelta < 0} skips thermal. Default no-op for headless test worlds. Kept on the seam
     * (not dormant): {@link #writeBackColumn} calls it in production.
     */
    default void noteSettle(BatchEntry entry, float maxMassDelta, float maxTempDelta) { }

    /**
     * Record the material each cell's just-persisted mass now belongs to (DESIGN §10 follow-on).
     * Called from the writeback loop AFTER a successful advection writeBack with the engine's output
     * species ({@code outMat}, may be null for stub/back-compat) and the batch {@code lut}. The live
     * impl updates its CellMaterialTracker so the NEXT snapshot's ColumnAssembler seed gate and
     * InjectionDrain incumbent lookup read the engine's output species as last cycle's identity
     * (identity itself is durable in the SectionStore). Default no-op for headless test worlds. Kept
     * on the seam (not dormant): {@link #writeBackColumn} calls it in production.
     */
    default void recordCellMaterials(BatchEntry entry, char[] outMat, java.util.List<Material> lut) { }

    // ----------------------------------------------------------------------------------------
    // Whole-region column path (DESIGN 2026-06-01 §5–§7). The only live transport path: the
    // active+apron column set is stepped as one engine World (the per-section snapshot/writeBack
    // path it replaced has been retired).
    // ----------------------------------------------------------------------------------------

    /** One full-height column ({@link ColumnTask}, {@link net.rainbowcreation.orge.engine.RegionMarshaller#CHUNK_N}
     *  cells) plus the key it came from for write-back. */
    record ColumnEntry(Identifier dimension, int cx, int cz, ColumnTask task) {}

    /** A cycle's worth of column work: the dimension-tagged columns + the shared stable material table,
     *  the {@code lutEpoch} selecting the engine-resident table, plus this cycle's drained placement
     *  injections (and the intents they came from, for clear-on-success). */
    record ColumnBatch(List<ColumnEntry> entries, List<Material> lut, int lutEpoch,
                       List<net.rainbowcreation.orge.engine.EngineInjection> injections,
                       List<PendingInjections.Intent> drained) {
        /** Convenience for an empty / no-real-table cycle: no injections, epoch 0. */
        public ColumnBatch(List<ColumnEntry> entries, List<Material> lut) {
            this(entries, lut, 0, List.of(), List.of());
        }
    }

    /**
     * Assemble this cycle's active+apron column set (the player-sphere union at {@code range}
     * projected to columns, expanded by one ring of LOADED neighbour columns — the apron;
     * MC-unloaded neighbours are excluded so they read as an absent-column wall). Each entry is a
     * full-height {@link ColumnTask} assembled via {@link ColumnAssembler}. Returns an empty batch
     * when there is nothing to simulate. Default empty for headless test worlds that supply their own
     * batch.
     */
    default ColumnBatch snapshotColumns(int range) {
        return new ColumnBatch(List.of(), List.of(MaterialLut.VACUUM));
    }

    /**
     * Persist one validated column result (T + mass) back into the 24 sections of {@code (cx,cz)},
     * reconcile MC blocks to the engine output species, record the output materials as the next
     * cycle's signature, settle, and wake any neighbour column that gained mass across an X/Z
     * boundary. Default no-op for headless test worlds.
     */
    default void writeBackColumn(ColumnEntry entry, ColumnResult result) { }

    /**
     * The placement-injection queue (spec Part B). Default impl returns a shared empty queue whose
     * {@code remove}/{@code forgetColumn} are harmless no-ops, so non-Minecraft {@link ThermalWorld}
     * impls + headless test fakes need no change; {@code MinecraftThermalWorld} overrides it with the
     * real server-thread queue.
     */
    default PendingInjections pendingInjections() {
        return PendingInjections.EMPTY;
    }
}
