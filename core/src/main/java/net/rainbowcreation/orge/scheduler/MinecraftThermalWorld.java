package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;

import java.util.List;

/**
 * Live {@link ThermalWorld} over a running {@link MinecraftServer} (DESIGN §8; whole-region rewire
 * 2026-06-01). The only Minecraft-coupled class in the scheduler, now a thin facade over three deep
 * modules that share its injected state (stores / cell-material tracker / active set / injection queue)
 * and its server-thread-confined mutable lifecycle fields (the bound server, the reconcile/phase seams,
 * and the most-recent batch LUT):
 *
 * <ul>
 *   <li>{@link BlockChangeCapture} — world event in → SectionStore / PendingInjections write out
 *       (the {@link WakeSink}, per-block placement/break capture, durable identity, biome ambient).</li>
 *   <li>{@link ColumnSnapshot} — range in → {@link ColumnBatch} out (player-sphere/apron union,
 *       per-column assembly, injection-intent drain).</li>
 *   <li>{@link ColumnWriteBack} — one {@link ColumnEntry}+{@link ColumnResult} in → its 24 sections
 *       persisted out (reconcile / phase / settle / neighbour-wake).</li>
 * </ul>
 *
 * <p>{@link #snapshotColumns}/{@link #writeBackColumn} touch the server-thread-confined
 * {@link SectionStoreManager} and MUST be called on the server thread; the {@code volatile server}
 * field is the only cross-thread state. {@link ColumnSnapshot} publishes its batch LUT through
 * {@link #publishLastColumnLut} and {@link ColumnWriteBack} reads it back through {@link #lastColumnLut}
 * — both run on the server thread in the same cycle, so the plain field is safe.</p>
 */
public final class MinecraftThermalWorld implements ThermalWorld {

    /** The bound server (the only cross-thread state); read lazily by the capture + snapshot modules. */
    private volatile MinecraftServer server;

    /** §10/§7 seams the column write-back drives per section (set in {@link Orge} after construction;
     *  default NOOP so headless tests that never set them do not need a live reconciler/phase changer). */
    private FluidReconciler fluidReconciler = FluidReconciler.NOOP;
    private PhaseChanger phaseChanger = PhaseChanger.NOOP;

    /** The material LUT of the most recent {@link #snapshotColumns} batch, threaded from the snapshot
     *  module to the write-back module (whose signature carries no LUT) so the reconciler can resolve the
     *  engine output species. Both run on the server thread in the same cycle, so a plain field is safe. */
    private List<Material> lastColumnLut = List.of(MaterialLut.VACUUM);

    /** Placement-injection queue (spec Part B). Server-thread-confined: written by the capturing wake
     *  sink, drained at snapshot, cleared on a successful write-back. */
    private final PendingInjections pendingInjections = new PendingInjections();

    private final BlockChangeCapture blockChangeCapture;
    private final ColumnSnapshot columnSnapshot;
    private final ColumnWriteBack columnWriteBack;

    public MinecraftThermalWorld(SectionStoreManager stores, CellMaterialTracker cellMaterials,
                                 ActiveSet activeSet) {
        this.blockChangeCapture = new BlockChangeCapture(
                stores, cellMaterials, activeSet, pendingInjections, this::server);
        this.columnSnapshot = new ColumnSnapshot(
                stores, cellMaterials, activeSet, pendingInjections, this::server, this::publishLastColumnLut);
        this.columnWriteBack = new ColumnWriteBack(
                stores, cellMaterials, activeSet, this::fluidReconciler, this::phaseChanger, this::lastColumnLut);
    }

    /** Convenience for headless write-back tests that supply their own LUT / never bind a server. */
    public MinecraftThermalWorld(SectionStoreManager stores) {
        this(stores, new CellMaterialTracker(), new ActiveSet());
    }

    /** Wire the per-section reconcile/phase seams the column {@link #writeBackColumn} drives (DESIGN
     *  2026-06-01 §7: write-back persists then reconciles+phase-changes each of the 24 sections). */
    public void setReconcilers(FluidReconciler fluidReconciler, PhaseChanger phaseChanger) {
        this.fluidReconciler = fluidReconciler;
        this.phaseChanger = phaseChanger;
    }

    /** The placement-injection queue (server-thread). Drained at snapshot, cleared on successful write-back. */
    @Override
    public PendingInjections pendingInjections() {
        return this.pendingInjections;
    }

    /** The wake sink the loader event hooks push into (DESIGN §10 Decision 11). */
    public WakeSink wakeSink() {
        return blockChangeCapture.wakeSink();
    }

    /** Bind the running server (on SERVER_STARTED); unbind on stop. */
    public void bindServer(MinecraftServer server) { this.server = server; }
    public void unbindServer() { this.server = null; }

    // Server thread only — delegated to the snapshot / write-back modules.
    @Override
    public ColumnBatch snapshotColumns(int range) {
        return columnSnapshot.snapshotColumns(range);
    }

    @Override
    public void writeBackColumn(ColumnEntry entry, ColumnResult result) {
        columnWriteBack.writeBackColumn(entry, result);
    }

    @Override
    public void noteSettle(BatchEntry entry, float maxMassDelta, float maxTempDelta) {
        columnWriteBack.noteSettle(entry, maxMassDelta, maxTempDelta);
    }

    @Override
    public void recordCellMaterials(BatchEntry entry, char[] outMat, List<Material> lut) {
        columnWriteBack.recordCellMaterials(entry, outMat, lut);
    }

    /** Event-driven BREAK (spec durable-material Part 4): turn the cell into durable {@code orge:vacuum}.
     *  Package-visible delegate for the headless suite (the wake path bails when the server is unbound). */
    void captureBreak(Identifier dim, int blockX, int blockY, int blockZ) {
        blockChangeCapture.captureBreak(dim, blockX, blockY, blockZ);
    }

    /** Test seam: pre-seed the batch LUT a headless {@link #writeBackColumn} resolves species against
     *  (the live path sets it from the registered material table at snapshot). */
    void setLastColumnLutForTest(List<Material> lut) { this.lastColumnLut = lut; }

    /** Persist the placed block's first-touch material as the cell's durable {@link SectionStore} identity
     *  (spec Part 3). Package-visible static delegate for the headless suite. */
    static void recordDurableIdentity(SectionStore store, int cx, int cz, int sectionY,
                                      int sectionCell, Material live) {
        BlockChangeCapture.recordDurableIdentity(store, cx, cz, sectionY, sectionCell, live);
    }

    /**
     * Clamp a DERIVED intensive temperature to the engine's {@code [0,6000]} derive boundary
     * ({@link StepValidator#MIN_K}..{@link StepValidator#MAX_K}). Law §6/§7: this clamp lives ONLY at
     * the derive boundary (the engine-feed tIn in {@link ColumnSnapshot}, and the {@code /orge} display
     * in S7) — NEVER on the stored extensive E (clamping E [J] to a Kelvin range would destroy the
     * energy). A non-finite derive falls back to {@link StepValidator#MIN_K} (defensive).
     */
    static float clampDeriveBoundary(float t) {
        return StepValidator.clampDerivedKelvin(t); // shared derive-boundary clamp (DRY; see S7 /orge display)
    }

    // ---- Shared mutable state accessors, injected into the modules as method refs (server thread) ----

    private MinecraftServer server() { return this.server; }
    private FluidReconciler fluidReconciler() { return this.fluidReconciler; }
    private PhaseChanger phaseChanger() { return this.phaseChanger; }
    private List<Material> lastColumnLut() { return this.lastColumnLut; }
    private void publishLastColumnLut(List<Material> lut) { this.lastColumnLut = lut; }
}
