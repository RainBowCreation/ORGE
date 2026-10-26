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
     *  cells) plus the key it came from for write-back, and the waterlogged-block water its assembly seeded
     *  (booked as a boundary placement when its section is written back, issue #60). */
    record ColumnEntry(Identifier dimension, int cx, int cz, ColumnTask task, WaterlogSeeding.Seeds waterlog,
                       CapturedColumn captured) {
        /** A column whose assembly seeded no waterlogged-block water. */
        public ColumnEntry(Identifier dimension, int cx, int cz, ColumnTask task) {
            this(dimension, cx, cz, task, new WaterlogSeeding.Seeds());
        }

        /** A column assembled without a server-thread capture (headless): its write-back reads the live
         *  store when it is applied. */
        public ColumnEntry(Identifier dimension, int cx, int cz, ColumnTask task, WaterlogSeeding.Seeds waterlog) {
            this(dimension, cx, cz, task, waterlog, null);
        }
    }

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
     * A column snapshot split across threads (issue #51). {@link #captureColumns} runs on the server
     * thread and copies only what the snapshot reads from the world and the store; {@link #assemble}
     * builds the {@link ColumnBatch} from that copy on any thread — the scheduler runs it on its step
     * thread, so the per-cell assembly never costs the server tick.
     */
    interface CapturedColumns {
        /** Nothing in range: no batch to assemble, the scheduler stays idle. */
        boolean isEmpty();

        /** Build the batch from the capture. Touches no live world or store state; any thread. */
        ColumnBatch assemble();

        /**
         * Whether this capture took the cycle's sky pass with it (it runs during {@link #assemble} and lands
         * with the write-back), so a cycle that writes nothing back also loses its pass. Default {@code false}:
         * the pass already ran on the store before the capture.
         */
        default boolean defersSkyPass() {
            return false;
        }
    }

    /**
     * Server-thread half of {@link #snapshotColumns}: capture this cycle's column set at {@code range}.
     * The default assembles on the spot (headless worlds that supply a ready batch).
     */
    default CapturedColumns captureColumns(int range) {
        ColumnBatch batch = snapshotColumns(range);
        return new CapturedColumns() {
            @Override public boolean isEmpty() { return batch.entries().isEmpty(); }
            @Override public ColumnBatch assemble() { return batch; }
        };
    }

    /**
     * {@link #captureColumns(int)} for a cycle that also runs the sky pass for {@code forcingDtSeconds}
     * ({@code 0} = no pass this cycle). The default runs the pass first, on the calling (server) thread —
     * {@link #applySurfaceForcing} — so the capture reads the forced store; the live world runs it on the
     * step thread over the capture instead (issue #51).
     */
    default CapturedColumns captureColumns(int range, double forcingDtSeconds) {
        if (forcingDtSeconds > 0) {
            applySurfaceForcing(forcingDtSeconds);
        }
        return captureColumns(range);
    }

    /** The server-thread half of a column write-back prepared off-thread (issue #51). */
    interface PreparedWrite {
        /** Install the prepared column into the store and the world. Server thread only. */
        void apply();
    }

    /**
     * Off-thread half of {@link #writeBackColumn}: everything per-cell is computed now, on the calling
     * thread, from {@code entry}'s capture; {@link PreparedWrite#apply} installs it on the server thread.
     * {@code lut} is the batch material table. The default defers the whole write-back to apply time
     * (headless worlds).
     */
    default PreparedWrite prepareWriteBack(ColumnEntry entry, ColumnResult result, List<Material> lut) {
        return () -> writeBackColumn(entry, result);
    }

    /**
     * Run the once-per-second sky heightmap pass (spec 2026-10-25): force the surface of the columns
     * being stepped for {@code dtSeconds} of solar gain / radiational loss into their stored E, booked in
     * the forcing ledger. Called by the scheduler while no step is in flight, before
     * {@link #snapshotColumns}. Default no-op for headless test worlds.
     */
    default void applySurfaceForcing(double dtSeconds) { }

    /**
     * Apply the queued thirsty-farmland drains (spec 2026-10-25-thirsty-farmland §3) to the store. Called by
     * the scheduler while no step is in flight, before {@link #snapshotColumns}. Default no-op for headless
     * test worlds.
     */
    default void applyHydrationDrain() { }

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
