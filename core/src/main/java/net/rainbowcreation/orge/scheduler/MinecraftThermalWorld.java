package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.RegionStore;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
 * <p>{@link #captureColumns}, {@link #snapshotColumns}, {@link #writeBackColumn} and the
 * {@link PreparedWrite#apply} half of {@link #prepareWriteBack} touch the server-thread-confined
 * {@link SectionStoreManager} and MUST be called on the server thread. The scheduler's step thread runs
 * {@link CapturedColumns#assemble} and {@link #prepareWriteBack} for a captured column; they read only the
 * capture (issue #51). The {@code volatile server} field is the only other cross-thread state. {@link ColumnSnapshot} publishes its batch LUT through
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

    /** The sky forcing pass's boundary energy ledger (spec 2026-10-25). Server-thread-confined. */
    private final SurfaceForcing surfaceForcing = new SurfaceForcing();

    /** The thirsty-farmland drain queue + its boundary ledger (spec 2026-10-25-thirsty-farmland).
     *  Server-thread-confined: filled by the farmland mixin, applied while IDLE. */
    private final HydrationDrain hydrationDrain = HydrationDrain.fromSystemProperty();

    /** The rain the same sky pass seeds, and its mass + energy ledgers (issue #33). Server-thread-confined. */
    private final RainSeeding rainSeeding = new RainSeeding();

    /** The daytime evaporation the same sky pass runs, and its boundary ledgers (issue #34). Server-thread-confined. */
    private final Evaporation evaporation = new Evaporation();

    /** The waterlogged-block water seed's boundary ledgers (issue #60), booked at write-back. Server-thread-confined. */
    private final WaterlogSeeding waterlogSeeding = new WaterlogSeeding();

    /** One column of the most recent batch, by address only (the batch's arrays are not retained). */
    private record BatchColumn(Identifier dimension, int cx, int cz) {}

    /** The columns of the most recent batch (active + apron): the set the sky forcing pass forces. */
    private List<BatchColumn> lastBatchColumns = List.of();

    private final SectionStoreManager stores;
    private final ActiveSet activeSet;
    private final BlockChangeCapture blockChangeCapture;
    private final ColumnSnapshot columnSnapshot;
    private final ColumnWriteBack columnWriteBack;

    public MinecraftThermalWorld(SectionStoreManager stores, CellMaterialTracker cellMaterials,
                                 ActiveSet activeSet) {
        this.stores = stores;
        this.activeSet = activeSet;
        this.blockChangeCapture = new BlockChangeCapture(
                stores, cellMaterials, activeSet, pendingInjections, this::server);
        this.columnSnapshot = new ColumnSnapshot(
                stores, cellMaterials, activeSet, pendingInjections, this::server, this::publishLastColumnLut);
        this.columnWriteBack = new ColumnWriteBack(
                stores, cellMaterials, activeSet, this::fluidReconciler, this::phaseChanger, this::lastColumnLut,
                waterlogSeeding, blockChangeCapture::changedSinceCapture);
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

    /** The bound server, or null when none is running (for server-thread callers that need world access). */
    public MinecraftServer boundServer() { return this.server; }

    // Server thread only — delegated to the snapshot / write-back modules.
    @Override
    public ColumnBatch snapshotColumns(int range) {
        blockChangeCapture.markCaptured();
        ColumnBatch batch = columnSnapshot.snapshotColumns(range);
        recordBatchColumns(batch.entries());
        return batch;
    }

    /** Server-thread capture of this cycle's columns; the scheduler assembles it on its step thread. */
    @Override
    public CapturedColumns captureColumns(int range) {
        return captureColumns(range, 0.0);
    }

    /**
     * Server-thread capture of this cycle's columns, with the sky pass for {@code forcingDtSeconds} (0 = none)
     * deferred to the step thread (issue #51: the pass walks every sky-exposed cell, the largest server-thread
     * cost left). Only each level's climate and the evaporation dither are taken here; on the step thread
     * the pass forces, rains on and evaporates the captured columns' stored sections — copy-on-write views
     * of the store, so the live sections are untouched — before they are assembled, and each column's share
     * of the pass's ledgers is booked when its write-back installs (a HELD or failed step lands neither).
     */
    @Override
    public CapturedColumns captureColumns(int range, double forcingDtSeconds) {
        blockChangeCapture.markCaptured();
        ColumnSnapshot.Captured captured = columnSnapshot.capture(range);
        lastBatchColumns = captured.columns().stream()
                .map(c -> new BatchColumn(c.dimension, c.cx, c.cz)).toList();
        MinecraftServer srv = this.server;
        if (!(forcingDtSeconds > 0) || srv == null || captured.isEmpty()) {
            return captured;
        }
        Function<Identifier, Material> lookup = memo(id -> ActiveMaterials.current().registry().get(id).orElse(null));
        Map<Identifier, SkyPass> passes = new HashMap<>();
        for (ServerLevel level : srv.getAllLevels()) {
            Identifier dim = level.dimension().identifier();
            SurfaceForcing.Climate climate = SurfaceForcing.Climate.forDimension(
                    dim, level.getDayTime(), level.getRainLevel(1f)).orElse(null);
            if (stores.store(dim) == null || climate == null) {
                continue; // no store, or a dimension with no sky (the Nether)
            }
            passes.put(dim, new SkyPass(climate, evaporation.nextDither(dim, climate, lookup)));
        }
        return new CapturedColumns() {
            @Override public boolean isEmpty() { return captured.isEmpty(); }

            @Override public boolean defersSkyPass() { return true; }

            @Override public ColumnBatch assemble() {
                Function<Identifier, Material> stepLookup =
                        memo(id -> ActiveMaterials.current().registry().get(id).orElse(null));
                for (CapturedColumn c : captured.columns()) {
                    SkyPass pass = passes.get(c.dimension);
                    if (pass != null) {
                        c.sky = forceCaptured(c, pass, forcingDtSeconds, stepLookup);
                    }
                }
                return captured.assemble();
            }
        };
    }

    /** One dimension's climate and evaporation dither for a sky pass. */
    record SkyPass(SurfaceForcing.Climate climate, double dither) {}

    /** One column's share of a sky pass's ledgers, booked into the world's when its write-back installs. */
    record SkyLedgers(SurfaceForcing forcing, RainSeeding rain, Evaporation evaporation) {}

    /** Step thread: the sky pass over one captured column's stored sections — forcing, rain, evaporation in
     *  ONE walk, exactly as {@link #applySurfaceForcing} runs it over the store — into fresh ledgers. */
    static SkyLedgers forceCaptured(CapturedColumn c, SkyPass pass, double dtSeconds,
                                            Function<Identifier, Material> lookup) {
        SectionStore scratch = new SectionStore(SCRATCH_REGION, AmbientProvider.FALLBACK);
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            CapturedColumn.Section s = c.section(sectionY);
            if (s.stored()) {
                scratch.put(new SubchunkKey(c.cx, sectionY, c.cz), s.data()); // a view: copied on its first write
            }
        }
        SkyLedgers ledgers = new SkyLedgers(new SurfaceForcing(), new RainSeeding(), new Evaporation());
        SkyColumnPass.Visitor sky = ledgers.forcing().visitor(c.dimension, pass.climate(), dtSeconds, lookup)
                .andThen(ledgers.rain().visitor(c.dimension, pass.climate(), dtSeconds, lookup))
                .andThen(ledgers.evaporation().visitor(c.dimension, pass.climate(), dtSeconds, scratch, lookup,
                        pass.dither()));
        SkyColumnPass.walkAndPut(scratch, c.cx, c.cz, lookup, sky);
        return ledgers;
    }

    /** A region store the scratch store of {@link #forceCaptured} never reads or writes (no column is loaded). */
    private static final RegionStore SCRATCH_REGION = new RegionStore(java.nio.file.Path.of("orge-unused"));

    /** {@code lookup}, each id resolved once. */
    private static Function<Identifier, Material> memo(Function<Identifier, Material> lookup) {
        Map<Identifier, Optional<Material>> resolved = new HashMap<>();
        return id -> resolved.computeIfAbsent(id, k -> Optional.ofNullable(lookup.apply(k))).orElse(null);
    }

    /** Off-thread preparation of one column's write-back; the returned half applies on the server thread,
     *  and books the column's share of a sky pass that ran with its capture. */
    @Override
    public PreparedWrite prepareWriteBack(ColumnEntry entry, ColumnResult result, List<Material> lut) {
        PreparedWrite write = columnWriteBack.prepare(entry, result, lut);
        SkyLedgers sky = entry.captured() == null ? null : entry.captured().sky;
        if (sky == null) {
            return write;
        }
        return () -> {
            SectionStore store = stores.store(entry.dimension());
            if (store == null || !store.isLoaded(entry.cx(), entry.cz())) {
                return; // unloaded since the capture: nothing lands, nothing is booked
            }
            write.apply();
            surfaceForcing.absorb(sky.forcing());
            rainSeeding.absorb(sky.rain());
            evaporation.absorb(sky.evaporation());
        };
    }

    /** The sky pass (spec 2026-10-25 §4): each level's climate from its dimension, day time and rain, over
     *  that dimension's columns of the last batch — forcing the surface, seeding the rain (issue #33) and
     *  evaporating surface water by day (issue #34). */
    @Override
    public void applySurfaceForcing(double dtSeconds) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return;
        }
        Function<Identifier, Material> lookup = id -> ActiveMaterials.current().registry().get(id).orElse(null);
        for (ServerLevel level : srv.getAllLevels()) {
            applySurfaceForcing(level.dimension().identifier(), level.getDayTime(), level.getRainLevel(1f),
                    dtSeconds, lookup);
        }
    }

    /** Force, rain and evaporate on {@code dim}'s columns of the last batch under its climate — ONE walk per
     *  column for every consumer, no second heightmap scan (package seam for the headless suite). */
    void applySurfaceForcing(Identifier dim, long dayTime, float rainLevel, double dtSeconds,
                             Function<Identifier, Material> registryLookup) {
        // The pass resolves the same few ids for every exposed cell (its slots, the enthalpy curves' phase
        // chains): one registry lookup per id per pass (issue #51 — it runs on the server thread).
        Function<Identifier, Material> lookup = memo(registryLookup);
        SectionStore store = stores.store(dim);
        SurfaceForcing.Climate climate = SurfaceForcing.Climate.forDimension(dim, dayTime, rainLevel).orElse(null);
        if (store == null || climate == null) {
            return; // no store, or a dimension with no sky (the Nether)
        }
        SkyColumnPass.Visitor sky = surfaceForcing.visitor(dim, climate, dtSeconds, lookup)
                .andThen(rainSeeding.visitor(dim, climate, dtSeconds, lookup))
                .andThen(evaporation.visitor(dim, climate, dtSeconds, store, lookup));
        for (BatchColumn e : lastBatchColumns) {
            if (dim.equals(e.dimension()) && store.isLoaded(e.cx(), e.cz())) {
                SkyColumnPass.walkAndPut(store, e.cx(), e.cz(), lookup, sky);
            }
        }
    }

    /** The sky forcing ledger: cumulative boundary energy per dimension. */
    public SurfaceForcing surfaceForcing() { return surfaceForcing; }

    /** Apply the queued farmland drains over every dimension's store with the active material table. */
    @Override
    public void applyHydrationDrain() {
        applyHydrationDrain(id -> ActiveMaterials.current().registry().get(id).orElse(null));
    }

    /** Apply the queued farmland drains, waking each drained section (package seam for the headless suite). */
    void applyHydrationDrain(Function<Identifier, Material> lookup) {
        hydrationDrain.apply(stores::store, lookup, activeSet);
    }

    /** The thirsty-farmland drain: the queue the farmland mixin fills, and its boundary mass/energy ledger. */
    public HydrationDrain hydrationDrain() { return hydrationDrain; }

    /** The rain ledgers: cumulative seeded water mass and its enthalpy per dimension. */
    public RainSeeding rainSeeding() { return rainSeeding; }

    /** The waterlogged-block seed ledgers: cumulative seeded water mass and its enthalpy per dimension. */
    public WaterlogSeeding waterlogSeeding() { return waterlogSeeding; }

    /** The evaporation ledgers: vapor that left the domain, its energy, and all water evaporated per dimension. */
    public Evaporation evaporation() { return evaporation; }

    /** Test seam: the columns the next forcing pass reaches (the live path records them at snapshot). */
    void setLastBatchColumnsForTest(List<ColumnEntry> columns) { recordBatchColumns(columns); }

    private void recordBatchColumns(List<ColumnEntry> entries) {
        lastBatchColumns = entries.stream().map(e -> new BatchColumn(e.dimension(), e.cx(), e.cz())).toList();
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
