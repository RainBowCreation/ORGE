package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Live {@link ThermalWorld} over a running {@link MinecraftServer} (DESIGN §8; whole-region rewire
 * 2026-06-01). Builds the player-sphere (+ forced-chunk) union across all loaded levels, projects it
 * to the awake column set + a loaded apron ring, and assembles each column into a full-height
 * {@link ColumnTask} via {@link ColumnAssembler} (reading live blocks + the §5 {@link SectionStore}).
 * {@link #writeBackColumn} scatters the validated {@link ColumnResult} back into the 24 sections via
 * {@link ColumnSectionCodec}, then reconciles/phase-changes each. The only Minecraft-coupled class in
 * the scheduler. {@link #snapshotColumns}/{@link #writeBackColumn} touch the server-thread-confined
 * {@link SectionStoreManager} and MUST be called on the server thread (the {@code volatile server}
 * field is the only cross-thread state). The per-section {@link #writeBack} override is kept dormant.
 */
public final class MinecraftThermalWorld implements ThermalWorld {

    /** First-touch material of empty/ambient air. An EDIT that leaves a SIMULATED cell as orge:air is a
     *  REMOVAL → durable vacuum (spec durable-material Part 4): the substance left, so the cell becomes the
     *  index-0 sentinel, NOT a fabricated 1.2 kg air block. (Ambient air is only ever seeded by the
     *  assembler's first-touch on NEVER-SIMULATED cells.) */
    private static final Identifier AIR_ID = Identifier.fromNamespaceAndPath("orge", "air");

    private final SectionStoreManager stores;
    private final CellMaterialTracker cellMaterials;
    private final ActiveSet activeSet;
    private volatile MinecraftServer server;

    /** Placement-injection queue (spec Part B). Server-thread-confined: written by the capturing wake
     *  sink, drained at snapshot, cleared on a successful write-back. */
    private final PendingInjections pendingInjections = new PendingInjections();

    /** §10/§7 seams the column write-back drives per section (set in {@link Orge} after construction;
     *  default NOOP so headless tests that never set them do not need a live reconciler/phase changer). */
    private FluidReconciler fluidReconciler = FluidReconciler.NOOP;
    private PhaseChanger phaseChanger = PhaseChanger.NOOP;

    /** The material LUT of the most recent {@link #snapshotColumns} batch, threaded to
     *  {@link #writeBackColumn} (whose signature carries no LUT) so the reconciler can resolve the
     *  engine output species. Both run on the server thread in the same cycle, so a plain field is safe. */
    private List<Material> lastColumnLut = List.of(MaterialLut.VACUUM);

    /** Test seam: pre-seed the batch LUT a headless {@link #writeBackColumn} resolves species against
     *  (the live path sets it in {@link #snapshot} from the registered material table). */
    void setLastColumnLutForTest(List<Material> lut) { this.lastColumnLut = lut; }

    public MinecraftThermalWorld(SectionStoreManager stores, CellMaterialTracker cellMaterials,
                                 ActiveSet activeSet) {
        this.stores = stores;
        this.cellMaterials = cellMaterials;
        this.activeSet = activeSet;
    }

    /** Wire the per-section reconcile/phase seams the column {@link #writeBackColumn} drives (DESIGN
     *  2026-06-01 §7: write-back persists then reconciles+phase-changes each of the 24 sections). */
    public void setReconcilers(FluidReconciler fluidReconciler, PhaseChanger phaseChanger) {
        this.fluidReconciler = fluidReconciler;
        this.phaseChanger = phaseChanger;
    }

    /** Convenience for headless write-back tests that never call {@link #snapshot}. */
    public MinecraftThermalWorld(SectionStoreManager stores) {
        this(stores, new CellMaterialTracker(), new ActiveSet());
    }

    /** The placement-injection queue (server-thread). Drained at snapshot, cleared on successful write-back. */
    @Override
    public PendingInjections pendingInjections() {
        return this.pendingInjections;
    }

    /** The wake sink the loader event hooks push into (DESIGN §10 Decision 11). The composite runs the
     *  placement-injection capture first, then delegates the unchanged wake behaviour to {@link #activeSet}. */
    public WakeSink wakeSink() {
        return capturingWakeSink;
    }

    private final WakeSink capturingWakeSink = new WakeSink() {
        @Override
        public void wakeBlock(Identifier dim, int blockX, int blockY, int blockZ) {
            captureBlockChange(dim, blockX, blockY, blockZ);    // enqueue intent if a displacement placement
            activeSet.wakeBlock(dim, blockX, blockY, blockZ);   // unchanged wake behaviour
        }

        @Override
        public void wakeBreak(Identifier dim, int blockX, int blockY, int blockZ) {
            // Event-driven BREAK (spec Part 4): the BlockEvent.BREAK signal is authoritative — do NOT read
            // the (still-outgoing, pre-event) world block. Turn the cell into durable orge:vacuum and wake
            // the section so neighbours flow into it via normal advection.
            captureBreak(dim, blockX, blockY, blockZ);
            activeSet.wakeBlock(dim, blockX, blockY, blockZ);
        }

        @Override
        public void wakeFlowSection(Identifier dim, SubchunkKey key) {
            activeSet.wakeFlowSection(dim, key);
        }

        @Override
        public void wakeThermalSection(Identifier dim, SubchunkKey key) {
            activeSet.wakeThermalSection(dim, key);
        }
    };

    /** Server-thread: read the live block + the recorded incumbent at this cell and, under the unified
     *  substance model, (a) enqueue a displace-and-inject intent for ANY differing species
     *  ({@link PlacementInjectionPolicy} — solid or fluid alike captures the placed material's
     *  {@code defaultMass} and displaces the incumbent), and (b) record the placed block's first-touch
     *  material as the cell's DURABLE identity in the {@link SectionStore} (spec Part 3), so the
     *  placement persists against the next assemble even before the engine writes it back (the vanish
     *  race, now fixed for every species). The reconciler's own steady-state repaint (live == recorded
     *  incumbent) is still filtered out by the policy. Fully null/guard-safe (server null, level null,
     *  chunk not loaded, prior null) so it is inert in the headless suites where {@code server == null}.
     *  Shared wake path: PLACE/BREAK/FILL_BUCKET all route here; the event-driven break→vacuum path is
     *  introduced separately (Task G2) — this method records the live (post-event) block as-is. */
    private void captureBlockChange(Identifier dim, int blockX, int blockY, int blockZ) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            if (InjectDebug.on() && InjectDebug.throttle("bail-server", 500)) {
                InjectDebug.LOG.info("[capture] BAIL server==null (not bound) at ({},{},{})", blockX, blockY, blockZ);
            }
            return;
        }
        ServerLevel level = levelFor(srv, dim);
        if (level == null) {
            if (InjectDebug.on() && InjectDebug.throttle("bail-level", 500)) {
                InjectDebug.LOG.info("[capture] BAIL level==null for dim={} at ({},{},{})", dim, blockX, blockY, blockZ);
            }
            return;
        }
        int cx = SectionPos.blockToSectionCoord(blockX);
        int cz = SectionPos.blockToSectionCoord(blockZ);
        if (LiveMaterials.loadedChunk(level, cx, cz) == null) {
            if (InjectDebug.on() && InjectDebug.throttle("bail-chunk", 500)) {
                InjectDebug.LOG.info("[capture] BAIL chunk not loaded ({},{}) at ({},{},{})", cx, cz, blockX, blockY, blockZ);
            }
            return;
        }
        int sectionY = SectionPos.blockToSectionCoord(blockY);
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        ActiveMaterials.State mats = ActiveMaterials.current();

        // Live material from the world block (null for a non-ORGE block — the policy null-guards it).
        BlockPos pos = new BlockPos(blockX, blockY, blockZ);
        Material live = LiveMaterials.materialFor(level.getBlockState(pos).getBlock(), mats.registry());

        // Recorded incumbent (last cycle's engine-output species) for this cell, in section-local space.
        Identifier[] prior = cellMaterials.prior(dim, key);
        int lx = blockX & 15, ly = blockY & 15, lz = blockZ & 15;
        int sectionCell = lx + 16 * ly + 256 * lz;
        Material incumbent = (prior != null && sectionCell < prior.length && prior[sectionCell] != null)
                ? materialById(prior[sectionCell], mats) : null;

        int engineCell = ColumnSectionCodec.colIdx(lx, sectionY, ly, lz);
        float ambientK = biomeAmbientK(level, key);

        if (InjectDebug.on()) {
            logCapture(dim, cx, cz, blockX, blockY, blockZ, engineCell, key, sectionCell, prior,
                    level, pos, live, incumbent);
        }

        // Override-close (spec Part 4): an EDIT whose first-touch material is orge:air at a SIMULATED cell is
        // a REMOVAL → durable vacuum, NOT an air placement. (The substance left; ambient air is only ever
        // seeded by the assembler's first-touch on never-simulated cells.) This makes the explicit BREAK
        // event AND the post-break setBlock-to-air agree on vacuum, so the air write can no longer resurrect
        // a broken cell. A steady-state air↔air / air→vacuum repaint stays a NO-OP: if the recorded incumbent
        // is already air or vacuum, there is nothing to remove (don't enqueue a removal every reconcile cycle).
        if (live != null && AIR_ID.equals(live.id())) {
            Identifier incId = incumbent != null ? incumbent.id() : null;
            boolean alreadyEmpty = incId == null
                    || AIR_ID.equals(incId) || MaterialPalette.VACUUM_ID.equals(incId);
            if (!alreadyEmpty) {
                recordRemoval(stores.store(dim), dim, cx, cz, sectionY, sectionCell, engineCell);
            }
            return; // never enqueue an air PLACEMENT or record an air identity at a simulated cell
        }

        // Same-window break/flicker + same-species re-place: cancel a stale pending removal instead of
        // letting it stomp the cell to vacuum (solid flow-through) or force-injecting a fresh defaultMass
        // (movable-fluid mass fabrication). Any other case delegates to the ordinary displacement capture.
        PlacementCapture.captureOrCancelStaleRemoval(
                pendingInjections, dim, cx, cz, engineCell, live, incumbent, ambientK);

        // Durable identity (spec Part 3): the placed block's first-touch material becomes the cell's stored
        // material at once, so the placement persists even before the engine writes it back (vanish-race fix,
        // generalised to every species). live == firstTouchMaterial(placedBlock) already (computed above).
        recordDurableIdentity(stores.store(dim), cx, cz, sectionY, sectionCell, live);
    }

    /** Persist the placed block's first-touch material as the cell's durable {@link SectionStore} identity
     *  (spec Part 3), so the placement survives the next assemble before the engine writes it back. No-op
     *  when the block carries no ORGE material ({@code live == null}) or the column is absent/unloaded —
     *  guarding the store so a non-ORGE placement or an off-store cell is silently skipped. Package-visible
     *  so the headless suite can drive it against the real §5 store (the enclosing {@code captureBlockChange}
     *  bails at {@code server == null} and can't be driven there). */
    static void recordDurableIdentity(SectionStore store, int cx, int cz, int sectionY,
                                      int sectionCell, Material live) {
        if (live == null) return;
        if (store != null && store.isLoaded(cx, cz)) {
            store.setMaterialAt(cx, cz, sectionY, sectionCell, live.id());
        }
    }

    /**
     * Event-driven BREAK (spec durable-material Part 4): turn the cell into durable {@code orge:vacuum}.
     * Enqueues a removal intent (the {@link InjectionDrain} stomps the cell to the index-0 sentinel,
     * mass 0, and emits NO injection; neighbours then flow in via normal advection) AND records the
     * durable vacuum identity so the assembler keeps it vacuum next cycle (no {@code orge:air} re-seed).
     * Does NOT read the world block — the {@code BlockEvent.BREAK} signal is authoritative (at handler
     * time the block is still the outgoing block, not yet air). Fully guard-safe (store null / column
     * unloaded → only the durable-record is skipped; the removal intent is still queued).
     */
    void captureBreak(Identifier dim, int blockX, int blockY, int blockZ) {
        int cx = SectionPos.blockToSectionCoord(blockX);
        int cz = SectionPos.blockToSectionCoord(blockZ);
        int sectionY = SectionPos.blockToSectionCoord(blockY);
        int lx = blockX & 15, ly = blockY & 15, lz = blockZ & 15;
        int sectionCell = lx + 16 * ly + 256 * lz;
        int engineCell = ColumnSectionCodec.colIdx(lx, sectionY, ly, lz);
        recordRemoval(stores.store(dim), dim, cx, cz, sectionY, sectionCell, engineCell);
    }

    /**
     * Shared removal record (spec Part 4): enqueue the removal intent (drain → index-0 vacuum sentinel,
     * mass 0, no injection) and stamp the cell's durable {@link SectionStore} identity to
     * {@code orge:vacuum} so the assembler keeps it vacuum (no air re-seed). Both the explicit BREAK
     * event ({@link #captureBreak}) and the {@code captureBlockChange} air-edit override route here, so a
     * break and the subsequent setBlock-to-air agree on vacuum (closing the resurrection override). The
     * removal intent is always queued; the durable stamp is skipped (silently) when the store is absent
     * or the column unloaded.
     */
    private void recordRemoval(SectionStore store, Identifier dim, int cx, int cz, int sectionY,
                               int sectionCell, int engineCell) {
        pendingInjections.enqueueRemoval(dim, cx, cz, engineCell);
        if (store != null && store.isLoaded(cx, cz)) {
            store.setMaterialAt(cx, cz, sectionY, sectionCell, MaterialPalette.VACUUM_ID);
        }
    }

    /** DIAGNOSTIC ONLY (toggle {@code -Dorge.debug.inject}). Traces the per-block capture decision for
     *  any real material transition at a cell — what the live + recorded-incumbent materials are, the
     *  live material's defaultMass, the cell's stored mass/temp, and whether the placement is enqueued
     *  as an injection or skipped (with the reason). Filters steady-state engine-output repaints
     *  (live == incumbent) so only genuine placements/breaks/transitions are logged. No behaviour. */
    private void logCapture(Identifier dim, int cx, int cz, int blockX, int blockY, int blockZ,
                            int engineCell, SubchunkKey key, int sectionCell, Identifier[] prior,
                            ServerLevel level, BlockPos pos, Material live, Material incumbent) {
        Identifier liveId = live != null ? live.id() : null;
        Identifier incId = incumbent != null ? incumbent.id() : null;
        if (liveId != null && liveId.equals(incId)) {
            return; // steady-state repaint (engine output already recorded) — not a transition
        }
        String priorState = prior == null ? "NULL-array"
                : (sectionCell < prior.length && prior[sectionCell] != null ? "present" : "NULL-cell");
        String stored = "n/a";
        SectionStore store = stores.store(dim);
        if (store != null && store.isLoaded(cx, cz)) {
            SectionData d = store.get(key);
            if (d != null) {
                stored = "mass=" + d.massAt(sectionCell) + ",E=" + d.enthalpyAt(sectionCell);
            }
        }
        String decision;
        if (PlacementInjectionPolicy.isDisplacement(live, incumbent)) {
            decision = "ENQUEUE inject=" + liveId;
        } else if (live == null) {
            decision = "SKIP live-null (non-ORGE block / no material)";
        } else {
            decision = "SKIP self-write (live==incumbent repaint)";
        }
        InjectDebug.LOG.info(
                "[capture] pos=({},{},{}) cell={} block={} live={} defMass={} incumbent={} prior={} stored({}) -> {}",
                blockX, blockY, blockZ, engineCell, level.getBlockState(pos), InjectDebug.describe(live),
                live != null ? live.defaultMass() : Float.NaN, InjectDebug.describe(incumbent),
                priorState, stored, decision);
    }

    /** id→{@link Material} in the active state, or {@code null} when the id is absent (registry not yet
     *  populated, or the recorded species is no longer defined). Mirrors {@link LiveMaterials}' lookup but
     *  null-safe (never the fallback) so an unknown incumbent reads as "nothing known to displace". */
    private static Material materialById(Identifier id, ActiveMaterials.State mats) {
        return mats.registry().get(id).orElse(null);
    }

    /** The {@link ServerLevel} for {@code dim}, or null. Mirrors {@code MinecraftPhaseChanger#levelFor}. */
    private static ServerLevel levelFor(MinecraftServer srv, Identifier dim) {
        for (ServerLevel level : srv.getAllLevels()) {
            if (level.dimension().identifier().equals(dim)) {
                return level;
            }
        }
        return null;
    }

    /** Bind the running server (on SERVER_STARTED); unbind on stop. */
    public void bindServer(MinecraftServer server) { this.server = server; }
    public void unbindServer() { this.server = null; }

    // Server thread only.
    @Override
    public void writeBack(BatchEntry entry, StepResult result) {
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(entry.key().cx(), entry.key().cz())) {
            return;
        }
        SectionData data = store.get(entry.key());
        // Persist the engine's per-cell mass (§10): advection now MOVES mass between cells, so the
        // authoritative post-step mass is result.mass() — no longer the snapshot geometry mass.
        // Conduction cycles carry mass through unchanged (the scheduler passes the snapshot mass
        // back in result.mass()), so this stays the block-derived geometry mass when no flow ran.
        // TODO(perf, §8 follow-on): enthalpyArray() force-promotes a UNIFORM ambient section to FULL (two 4096 arrays + fill) right before we overwrite every cell. A SectionData.setAllEnthalpies(float[]) that skips the fill would avoid the churn for first-touch sections.
        float[] massDst = data.massArray();
        System.arraycopy(result.mass(), 0, massDst, 0, SectionData.CELLS);
        // S6 (law §6/§7): this DORMANT per-section path's StepResult has no extensive-E channel — it
        // carries only the engine's derived Tout. The live column path stores eOut directly; here we
        // must ENCODE E = m·h(Tout) on the cell's enthalpy curve, NEVER store the raw T as E (a temp-
        // ghost). Per-cell species = stored material when present, else the index-0 vacuum sentinel.
        ActiveMaterials.State mats = ActiveMaterials.current();
        java.util.function.Function<Identifier, Material> lookup =
                id -> mats.registry().get(id).orElse(null);
        float[] tOut = result.temperature();
        float[] dst = data.enthalpyArray();
        for (int i = 0; i < SectionData.CELLS; i++) {
            Material cellM = lookup.apply(data.materialAt(i));
            float mi = massDst[i];
            // E = m·h(T); a massless / unresolvable cell carries no enthalpy (E = 0).
            dst[i] = (cellM == null || mi <= 0f) ? 0f
                    : (float) EnthalpyCurve.cellE(mi, cellM, lookup, tOut[i]);
        }
        // writeBack force-promoted this section to FULL via the array accessors above. Collapse it
        // straight back to UNIFORM when the engine left every cell identical (a settled/flat section),
        // so FULL is not a one-way ratchet — observability (/orge get-live) and the on-disk form both
        // reflect the section's true state. No-op (cheap scan, returns false) while a gradient remains.
        data.demoteIfUniform();
        store.put(entry.key(), data);
    }

    /**
     * Feed a section's per-step settle deltas into the active set (DESIGN §10 Decision 11). A
     * negative delta means that pass did not run this cycle (skip its countdown), so a coincident
     * conduction tick still counts the flow pass and vice versa.
     */
    @Override
    public void noteSettle(BatchEntry entry, float maxMassDelta, float maxTempDelta) {
        if (maxMassDelta >= 0f) {
            activeSet.noteFlowDelta(entry.dimension(), entry.key(), maxMassDelta);
        }
        if (maxTempDelta >= 0f) {
            activeSet.noteThermalDelta(entry.dimension(), entry.key(), maxTempDelta);
        }
    }

    /** Trigger (c): a neighbour pushed mass across our shared seam — revive its flow pass so it
     *  re-enters the snapshot and accepts the incoming mass instead of stranding it at the border. */
    @Override
    public void wakeNeighbourFlow(Identifier dim, SubchunkKey neighbour) {
        activeSet.wakeFlowSection(dim, neighbour);
    }

    /**
     * Record the engine's OUTPUT species as the signature for this section's just-persisted mass
     * (DESIGN §10 follow-on; the reseed-misfire fix). Per cell the recorded species is the engine
     * output when present ({@code outMat[i] != 0}), else the cell's input/world material — so an
     * untouched air cell records {@code orge:air}, never the index-0 {@code orge:vacuum} sentinel. This
     * signature is the "last cycle's engine output" that the NEXT snapshot's ColumnAssembler seed gate
     * and the InjectionDrain incumbent lookup read — it is event-independent (a place/break overwrites
     * the durable {@code SectionStore} identity but NOT this recorder), so a freshly-placed cell still
     * carries its old engine-output species here while the store already holds the new one. When the
     * signature is unchanged the prior {@code Identifier[]} is reused
     * verbatim, avoiding the 4096-ref signature re-allocation; the small per-cell {@code char[]}
     * species scratch is still built each call.
     */
    @Override
    public void recordCellMaterials(BatchEntry entry, char[] outMat, List<Material> lut) {
        char[] inMat = entry.task().matIx();
        char[] effective = new char[inMat.length];
        for (int i = 0; i < inMat.length; i++) {
            effective[i] = (outMat != null && i < outMat.length && outMat[i] != 0) ? outMat[i] : inMat[i];
        }
        Identifier[] prior = cellMaterials.prior(entry.dimension(), entry.key());
        cellMaterials.record(entry.dimension(), entry.key(), liveMaterialIds(effective, lut, prior));
    }

    /**
     * Clamp a DERIVED intensive temperature to the engine's {@code [0,6000]} derive boundary
     * ({@link StepValidator#MIN_K}..{@link StepValidator#MAX_K}). Law §6/§7: this clamp lives ONLY at
     * the derive boundary (the engine-feed tIn here, and the {@code /orge} display in S7) — NEVER on the
     * stored extensive E (clamping E [J] to a Kelvin range would destroy the energy). A non-finite
     * derive falls back to {@link StepValidator#MIN_K} (defensive; {@code deriveT} returns a finite K).
     */
    static float clampDeriveBoundary(float t) {
        if (!Float.isFinite(t) || t < StepValidator.MIN_K) {
            return StepValidator.MIN_K;
        }
        return Math.min(t, StepValidator.MAX_K);
    }

    /**
     * Input mass for one section: the stored/advected mass when the section has been simulated,
     * otherwise the block-derived geometry seed (a never-tracked section's true initial state — air
     * 1.2 kg, solids/fluids at their {@link Material#defaultMass()}). The fresh-fluid seed (a fluid
     * cell the store reports empty ⇒ {@code defaultMass}) is NOT applied here: it is owned by the
     * single seed in {@link ColumnAssembler} (DESIGN 2026-06-01 §6 — exactly one fresh-fluid seed in
     * the pipeline). This method now only selects stored-vs-geometry, never fabricates fluid mass.
     */
    private float[] sectionMass(SectionStore store, SubchunkKey key,
                                GeometryAssembler.Geometry geo, MaterialLut lut) {
        boolean has = store != null && store.hasSection(key);
        // Never-simulated section: block-derived initial state (includes air/solid masses ColumnAssembler
        // cannot reconstruct). Simulated section: the advected/stored mass verbatim — the fresh-fluid
        // seed for a stored-empty fluid cell is applied downstream by ColumnAssembler, not here.
        return has ? store.get(key).massArray().clone() : geo.mass();
    }

    /**
     * The live per-cell material ids for this section, to record as the next cycle's signature. Reuses
     * {@code prior} verbatim when the live materials are identical (the overwhelming common case) so a
     * static section costs only a comparison pass, not a fresh 4096-ref allocation every snapshot.
     */
    private static Identifier[] liveMaterialIds(char[] matIx, List<Material> mats, Identifier[] prior) {
        if (prior != null && idsUnchanged(matIx, mats, prior)) {
            return prior;
        }
        Identifier[] ids = new Identifier[matIx.length];
        for (int i = 0; i < matIx.length; i++) {
            ids[i] = mats.get(matIx[i]).id();
        }
        return ids;
    }

    private static boolean idsUnchanged(char[] matIx, List<Material> mats, Identifier[] prior) {
        for (int i = 0; i < matIx.length; i++) {
            if (!mats.get(matIx[i]).id().equals(prior[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * The prior cycle's recorded engine-output species (a per-cell {@link Identifier} signature from
     * {@link CellMaterialTracker}) translated into the current batch LUT's index space, for the
     * {@link ColumnAssembler} seed gate. A {@code null} prior (never-tracked section) or an id absent
     * from this batch's LUT maps to 0 (void) — which differs from any real fluid index, so a genuinely
     * new placement still seeds. Same {@code char[SectionData.CELLS]} layout the assembler expects.
     */
    private static char[] priorSpeciesIndices(Identifier[] prior, MaterialLut lut) {
        char[] out = new char[SectionData.CELLS];
        if (prior == null) {
            return out; // all void → no recorded signature → every fresh fluid is a genuine placement
        }
        int n = Math.min(prior.length, out.length);
        for (int i = 0; i < n; i++) {
            if (prior[i] != null) {
                out[i] = lut.indexOf(prior[i]);
            }
        }
        return out;
    }

    /** Biome base temperature sampled once at the section centre, mapped to Kelvin. */
    private static float biomeAmbientK(ServerLevel level, SubchunkKey key) {
        int bx = (key.cx() << 4) + 8;
        int by = (key.sectionY() << 4) + 8;
        int bz = (key.cz() << 4) + 8;
        float base = level.getBiome(new BlockPos(bx, by, bz)).value().getBaseTemperature();
        return BiomeTemperature.toKelvin(base);
    }

    private void addForcedSections(ServerLevel level, Set<SubchunkKey> union) {
        for (long packed : level.getForceLoadedChunks().toLongArray()) {
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            LevelChunk chunk = LiveMaterials.loadedChunk(level, cx, cz);
            if (chunk == null) continue;
            int min = chunk.getMinSectionY();
            int count = chunk.getSectionsCount();
            for (int s = 0; s < count; s++) {
                union.add(new SubchunkKey(cx, min + s, cz));
            }
        }
    }

    // ============================================================================================
    // Whole-region column path (DESIGN 2026-06-01). snapshotColumns assembles the active+apron column
    // set into full-height ColumnTasks; writeBackColumn scatters a ColumnResult back into 24 sections.
    // ============================================================================================

    // Server thread only.
    @Override
    public ColumnBatch snapshotColumns(int range) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return new ColumnBatch(List.of(), List.of(MaterialLut.VACUUM));
        }
        ActiveMaterials.State mats = ActiveMaterials.current();
        MaterialLut lut = new MaterialLut(mats.orderedMaterials(), mats.materialSlots());
        List<ColumnEntry> entries = new ArrayList<>();

        for (ServerLevel level : srv.getAllLevels()) {
            Identifier dim = level.dimension().identifier();

            Set<SubchunkKey> anchors = new HashSet<>();
            for (ServerPlayer p : level.players()) {
                anchors.add(new SubchunkKey(
                        SectionPos.blockToSectionCoord(p.getBlockX()),
                        SectionPos.blockToSectionCoord(p.getBlockY()),
                        SectionPos.blockToSectionCoord(p.getBlockZ())));
            }
            if (anchors.isEmpty() && level.getForceLoadedChunks().isEmpty()) {
                continue;
            }
            Set<SubchunkKey> union = SphereUnion.expand(anchors, range);
            addForcedSections(level, union);

            // Project the active section set to the awake COLUMN set (any active section ⇒ its column
            // is awake), preserving the §10 dormancy gate (a fully-asleep section contributes nothing,
            // a calm column drops out). activeWithin records new-in-range keys, same as the section path.
            List<SubchunkKey> active = activeSet.activeWithin(dim, new ArrayList<>(union));
            LinkedHashSet<Long> awakeColumns = new LinkedHashSet<>();
            for (SubchunkKey key : active) {
                awakeColumns.add(packColumn(key.cx(), key.cz()));
            }
            // Apron (DESIGN §5/decision 7): expand by one ring of LOADED neighbour columns so a
            // loaded-but-dormant neighbour is stepped as a real column (not misread as an absent-column
            // wall). A genuinely MC-unloaded neighbour is excluded → correct wall.
            LinkedHashSet<Long> columns = new LinkedHashSet<>(awakeColumns);
            for (long packed : awakeColumns) {
                int cx = unpackCx(packed);
                int cz = unpackCz(packed);
                addLoadedNeighbour(level, columns, cx - 1, cz);
                addLoadedNeighbour(level, columns, cx + 1, cz);
                addLoadedNeighbour(level, columns, cx, cz - 1);
                addLoadedNeighbour(level, columns, cx, cz + 1);
            }

            SectionStore store = stores.store(dim);
            for (long packed : columns) {
                int cx = unpackCx(packed);
                int cz = unpackCz(packed);
                if (LiveMaterials.loadedChunk(level, cx, cz) == null) {
                    continue; // unloaded since selection: absent column = wall
                }
                ColumnAssembler.SectionSource src =
                        columnSource(level, dim, store, lut, mats);
                entries.add(new ColumnEntry(dim, cx, cz,
                        ColumnAssembler.assemble(cx, cz, lut, mats.registry(), src)));
            }
        }
        // ---- Drain placement intents into this batch's injection list (spec B3) ----
        // ORDERING IS LOAD-BEARING: this drain MUST run after all ColumnAssembler.assemble
        // calls above. InjectionDrain.applyToColumn stomps the assembled cell
        // back to its recorded incumbent for each injected cell (see InjectionDrain class Javadoc).
        // If this block were moved before the per-column assemble loop, the stomp would target
        // uninitialized arrays and the assembler would subsequently re-seed the new species,
        // fabricating a double-placement. Do NOT reorder.
        List<net.rainbowcreation.orge.engine.EngineInjection> injections = new ArrayList<>();
        List<PendingInjections.Intent> drained = new ArrayList<>();
        for (int columnId = 0; columnId < entries.size(); columnId++) {
            ColumnEntry e = entries.get(columnId);
            List<PendingInjections.Intent> colIntents =
                    pendingInjections.peekColumn(e.dimension(), e.cx(), e.cz());
            if (colIntents.isEmpty()) {
                continue;
            }
            SectionStore store = stores.store(e.dimension());
            char[] colMat = e.task().matIx();
            float[] colMass = e.task().mass();
            int injBefore = injections.size();
            // Resolver registers the placed species into the batch LUT (appends if absent) so a
            // just-placed / stomped fluid that is not yet anywhere in the live snapshot is still a
            // valid injection species — the fix for the "placement species not in batch LUT" drop.
            InjectionDrain.SpeciesResolver resolver = id -> {
                Material m = materialById(id, mats);
                return m != null ? lut.indexOf(m) : 0;
            };
            InjectionDrain.applyToColumn(columnId, colMat, colMass, resolver, colIntents,
                    engineCell -> recordedIncumbentId(e.dimension(), e.cx(), e.cz(), engineCell),
                    engineCell -> storedMassAt(store, e.cx(), e.cz(), engineCell),
                    injections, drained);
            if (InjectDebug.on()) {
                int emitted = injections.size() - injBefore;
                InjectDebug.LOG.info("[drain] col=({},{}) intents={} emitted={}{}",
                        e.cx(), e.cz(), colIntents.size(), emitted,
                        emitted < colIntents.size()
                                ? " (emitted<intents => unregistered material, left queued)" : "");
            }
        }
        lastColumnLut = lut.materials();
        return new ColumnBatch(entries, lut.materials(), mats.lutEpoch(), injections, drained);
    }

    /** Recorded engine-output species id for an engine-cell in a column, or null if untracked. */
    private Identifier recordedIncumbentId(Identifier dim, int cx, int cz, int engineCell) {
        int x = engineCell & 15;
        int engineY = (engineCell / 16) % 384;
        int z = engineCell / 6144;
        int sectionY = Math.floorDiv(engineY - 64, 16);      // inverse of engineY = sectionY*16 + sy + 64
        int sy = engineY - 64 - sectionY * 16;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        Identifier[] prior = cellMaterials.prior(dim, key);
        int sectionCell = x + 16 * sy + 256 * z;
        return (prior != null && sectionCell < prior.length) ? prior[sectionCell] : null;
    }

    /** Stored mass (kg) for an engine-cell in a column. */
    private float storedMassAt(SectionStore store, int cx, int cz, int engineCell) {
        if (store == null) {
            return 0f;
        }
        int x = engineCell & 15;
        int engineY = (engineCell / 16) % 384;
        int z = engineCell / 6144;
        int sectionY = Math.floorDiv(engineY - 64, 16);
        int sy = engineY - 64 - sectionY * 16;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        if (!store.isLoaded(cx, cz)) {
            return 0f;
        }
        SectionData data = store.get(key);
        if (data == null) {
            return 0f;
        }
        int sectionCell = x + 16 * sy + 256 * z;
        return data.massAt(sectionCell);
    }

    /**
     * A per-section reader for {@link ColumnAssembler}: matIx from live blocks (via
     * {@link LiveMaterials}/{@link GeometryAssembler}); mass/T from the {@link SectionStore} (the
     * §10 advected state — the fresh-fluid seed is applied once by {@link ColumnAssembler}, not here),
     * or the per-cell ambient seed for a never-simulated / out-of-world section. A section not loaded
     * in the chunk is read as
     * full ambient air (matIx 0 / void with ambient T) — it is inside a present (loaded) column, so it
     * is a real defined cell, never "unknown".
     */
    private ColumnAssembler.SectionSource columnSource(ServerLevel level, Identifier dim,
                                                       SectionStore store, MaterialLut lut,
                                                       ActiveMaterials.State mats) {
        return (cx, cz, sectionY) -> {
            SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
            LevelChunk chunk = LiveMaterials.loadedChunk(level, cx, cz);
            LevelChunkSection section = chunk == null ? null : LiveMaterials.sectionOrNull(chunk, sectionY);
            float ambientK = biomeAmbientK(level, key);
            if (section == null) {
                // No block section here (above world top / empty subchunk): treat as void/ambient. matIx
                // 0 (void) → ColumnAssembler leaves it as-is (not a fluid, so no seed); mass 0; ambient T.
                char[] mat = new char[SectionData.CELLS];
                float[] mass = new float[SectionData.CELLS];
                float[] temp = new float[SectionData.CELLS];
                java.util.Arrays.fill(temp, ambientK);
                return new ColumnAssembler.SectionCells(mat, mass, temp);
            }
            final LevelChunkSection sec = section;
            GeometryAssembler.CellMaterials cellMat =
                    i -> LiveMaterials.materialFor(LiveMaterials.blockAt(sec, i), mats.registry());
            GeometryAssembler.Geometry geo = GeometryAssembler.assemble(cellMat, lut);
            float[] mass = sectionMass(store, key, geo, lut);
            // Last cycle's recorded engine-output species (the CellMaterialTracker signature) feeds the
            // ColumnAssembler seed gate and the InjectionDrain incumbent lookup below. Identity itself is
            // now durable (persisted into SectionStore at write-back), so there is no longer a block-diff
            // reseed here — material changes arrive as place/break EVENTS, not a snapshot-time diff.
            Identifier[] priorMat = cellMaterials.prior(dim, key);
            // Translate last cycle's recorded engine-output species into this section's LUT space for the
            // ColumnAssembler seed gate: a fluid cell at 0 kg is reseeded only when its label is NEW
            // relative to priorSpecies (genuine placement), never when the engine drained it (prior ==
            // current ⇒ no fabrication). An untracked section yields all-void ⇒ every fresh fluid seeds.
            char[] priorSpecies = priorSpeciesIndices(priorMat, lut);
            // Durable identity (durable-material §): when this section has a stored material layer, the
            // stored id is AUTHORITATIVE per cell (a broken cell stays orge:vacuum). The assembler resolves
            // it into the batch LUT by appending. Sections with no stored layer keep storedMaterial null ⇒
            // the assembler falls back to the block's first-touch geo.matIx().
            Identifier[] storedMaterial = new Identifier[SectionData.CELLS];
            if (store != null && store.hasSection(key) && store.get(key).hasMaterials()) {
                for (int i = 0; i < SectionData.CELLS; i++) {
                    storedMaterial[i] = store.materialAt(cx, cz, sectionY, i);
                }
            }
            // Velocity + dynamic pressure + STORED absolute E: read per-cell stored values from the
            // SectionStore when available; otherwise zero-fill (never-simulated / back-compat default).
            // Threading p back in is what lets depth-pressure ACCUMULATE across engine steps (the JNI
            // rebuilds a fresh World each call, so without persisting p it would reset to 0 every step).
            // S6 (law §6/§7): SectionCells.enthalpy carries the raw STORED extensive E [J] (loss-free to
            // the engine's eIn); the engine's TEMPERATURE channel (tIn diagnostic) carries a DERIVED,
            // CLAMPED T = h⁻¹(E/m) per cell — NEVER the raw E. Never-simulated cells: E = 0 (void/empty).
            float[] velX = new float[SectionData.CELLS];
            float[] velY = new float[SectionData.CELLS];
            float[] velZ = new float[SectionData.CELLS];
            float[] p    = new float[SectionData.CELLS];
            float[] swapReady = new float[SectionData.CELLS];
            float[] enthalpy = new float[SectionData.CELLS];
            // The engine tIn channel: for a stored section it is DERIVED from E below; for a never-
            // simulated section it is the per-cell ambient/source seed.
            float[] temps;
            if (store != null && store.hasSection(key)) {
                SectionData sd = store.get(key);
                java.util.function.Function<Identifier, Material> lookup =
                        id -> mats.registry().get(id).orElse(null);
                boolean hasMaterials = sd.hasMaterials();
                temps = new float[SectionData.CELLS];
                for (int i = 0; i < SectionData.CELLS; i++) {
                    float mi = mass[i];
                    // Velocity is DERIVED from stored extensive momentum: v = p/mass (law §7 — raw v is
                    // never stored). Guard mass>0 (else resting 0).
                    if (mi > 0f) {
                        velX[i] = sd.momXAt(i) / mi;
                        velY[i] = sd.momYAt(i) / mi;
                        velZ[i] = sd.momZAt(i) / mi;
                    }
                    p[i]    = sd.pAt(i);
                    swapReady[i] = sd.swapReadyAt(i);
                    // Raw stored absolute E [J] handed to the engine loss-free (NEVER as Kelvin).
                    enthalpy[i] = sd.enthalpyAt(i);
                    // Derive the engine's diagnostic tIn = h⁻¹(E/m), clamped to the [0,6000] derive
                    // boundary. Per-cell species = stored material when this section carries a durable
                    // layer, else the cell's first-touch block material. A massless / unresolvable cell
                    // derives to ambient (no curve). This is the law-§6/§7 fix: the engine never receives
                    // raw E in its Kelvin slot.
                    Material cellM = hasMaterials ? lookup.apply(sd.materialAt(i)) : cellMat.at(i);
                    float t = (cellM == null) ? ambientK
                            : EnthalpyCurve.deriveT(sd.enthalpyAt(i), mi, cellM, lookup, ambientK);
                    temps[i] = clampDeriveBoundary(t);
                }
            } else {
                temps = AmbientSeeder.seed(cellMat::at, ambientK);
            }
            return new ColumnAssembler.SectionCells(geo.matIx(), mass, temps, priorSpecies, storedMaterial,
                    velX, velY, velZ, p, swapReady, enthalpy);
        };
    }

    // Server thread only.
    @Override
    public void writeBackColumn(ColumnEntry entry, ColumnResult result) {
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(entry.cx(), entry.cz())) {
            return;
        }
        float maxMassDelta = 0f;
        float[] inMass = entry.task().mass();
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            SubchunkKey key = new SubchunkKey(entry.cx(), sectionY, entry.cz());
            float[] outMass = ColumnSectionCodec.sliceSectionChannel(result.mass(), sectionY);
            // Input temperature slice — carried into the reconstructed per-section StepTask below only as
            // a diagnostic (the §10/§7 seams derive their own T from stored E); never stored as E.
            float[] inT = ColumnSectionCodec.sliceSectionChannel(entry.task().temperature(), sectionY);
            // Mass clamped to [0, fullMassBound] (the column's max defaultMass over its species). The T
            // channel is NO LONGER the stored thermal truth (S6): stored E is. We slice the engine's
            // AUTHORITATIVE energy below; the result.temperature() (legacy derived tOut) is not stored.
            float[] cleanM = StepValidator.cleanMass(outMass, fullMassBound(entry.task()));
            char[] outMat = ColumnSectionCodec.sliceSectionMaterials(result.matIx(), sectionY);
            char[] inMatSec = ColumnSectionCodec.sliceSectionMaterials(entry.task().matIx(), sectionY);

            SectionData data = store.get(key);
            // S6 (law §6/§7): store the engine's AUTHORITATIVE extensive energy eOut (the S5-threaded
            // ColumnResult.enthalpy) UNCLAMPED. E is extensive [J]; the [0,6000] StepValidator clamp is a
            // KELVIN range and would destroy mid-plateau energy (E ≫ 6000 J) — it must NEVER touch E.
            // Only non-finite E is sanitized (defensive) to a safe 0; the magnitude is stored raw.
            float[] secE = ColumnSectionCodec.sliceSectionChannel(result.enthalpy(), sectionY);
            float[] dstE = data.enthalpyArray();
            for (int i = 0; i < SectionData.CELLS; i++) {
                float e = secE[i];
                dstE[i] = Float.isFinite(e) ? e : 0f;   // sanitize non-finite only — NO magnitude clamp
            }
            float[] dstM = data.massArray();
            System.arraycopy(cleanM, 0, dstM, 0, SectionData.CELLS);
            // Momentum write-back (S6, law §7): store EXTENSIVE momentum p = m·vOut, NOT the raw engine
            // velocity (which would be a velocity-ghost on reload). Sanitize non-finite v → 0 first, then
            // multiply by the WRITTEN-BACK per-cell mass (cleanM) so p = m·v is consistent with stored
            // mass. A massless cell ⇒ momentum 0 (m·v = 0) ⇒ no velocity-ghost when a cell is thinned.
            // The section is already FULL from the E/mass array writes above, so momXArray() etc. allocate
            // safely. Does NOT gate mass conservation.
            float[] secVx = ColumnSectionCodec.sliceSectionChannel(result.velX(), sectionY);
            float[] secVy = ColumnSectionCodec.sliceSectionChannel(result.velY(), sectionY);
            float[] secVz = ColumnSectionCodec.sliceSectionChannel(result.velZ(), sectionY);
            float[] cleanVx = StepValidator.cleanVelocity(secVx, null);
            float[] cleanVy = StepValidator.cleanVelocity(secVy, null);
            float[] cleanVz = StepValidator.cleanVelocity(secVz, null);
            float[] momX = data.momXArray();
            float[] momY = data.momYArray();
            float[] momZ = data.momZArray();
            for (int i = 0; i < SectionData.CELLS; i++) {
                float m = cleanM[i];
                momX[i] = m * cleanVx[i];
                momY[i] = m * cleanVy[i];
                momZ[i] = m * cleanVz[i];
            }
            // Dynamic-pressure write-back: slice the single p channel, sanitize non-finite AND clamp
            // negatives to 0 (p >= 0 — a free surface is p=0), then persist into the SectionData p array.
            // Persisting p is what makes depth-pressure survive across engine steps and save/load.
            float[] secP = ColumnSectionCodec.sliceSectionChannel(result.p(), sectionY);
            float[] cleanP = StepValidator.cleanPressure(secP, null);
            System.arraycopy(cleanP, 0, data.pArray(), 0, SectionData.CELLS);
            // T10c: persist the §5.3 swap-cadence accumulator in-memory (law #7 bookkeeping; NOT serialized)
            // so swaps fire across scheduler calls. swapReady is a non-negative accumulator, so reuse
            // cleanPressure's sanitizer (strips NaN/inf AND clamps <0 → 0) — the exact non-negative guard;
            // it cannot wipe legitimate positive accumulation since the engine emits >=0.
            float[] secSr = ColumnSectionCodec.sliceSectionChannel(result.swapReady(), sectionY);
            float[] cleanSr = StepValidator.cleanPressure(secSr, null);
            System.arraycopy(cleanSr, 0, data.swapReadyArray(), 0, SectionData.CELLS);
            // Durable identity (durable-material §, keystone-closing half): persist each cell's
            // engine-output material id into the store so next cycle E1's columnSource reads it as
            // authoritative (hasMaterials()==true). Effective-species rule mirrors recordCellMaterials:
            // engine output when present, else the input/world material — so an untouched air cell records
            // orge:air, never the index-0 orge:vacuum sentinel; a genuinely empty/broken cell whose
            // effective species is index 0 records orge:vacuum. setMaterialAt promotes to FULL (already
            // FULL from the array writes), allocates the palette and interns the id (mirrors mass/temp).
            for (int i = 0; i < SectionData.CELLS; i++) {
                char sp = (outMat != null && i < outMat.length && outMat[i] != 0) ? outMat[i] : inMatSec[i];
                data.setMaterialAt(i, lastColumnLut.get(sp).id());
            }
            data.demoteIfUniform();
            store.put(key, data);

            // Reconstruct the per-section entry the §10/§7 seams consume (input geometry + this section's
            // engine output species). recordCellMaterials/noteSettle/reconcile/phase mirror the
            // per-section advection write-back exactly.
            StepTask secTask = new StepTask(key, inMatSec, inMass(inMass, sectionY), inT,
                    NeighborHalo.empty());
            ThermalWorld.BatchEntry secEntry = new ThermalWorld.BatchEntry(entry.dimension(), key, secTask);
            recordCellMaterials(secEntry, outMat, lastColumnLut);
            float secMassDelta = maxAbsDelta(cleanM, secTask.mass());
            if (secMassDelta > maxMassDelta) maxMassDelta = secMassDelta;
            noteSettle(secEntry, secMassDelta, -1f);
            phaseChanger.applyPhaseChanges(secEntry, outMat, lastColumnLut);
            fluidReconciler.reconcile(secEntry, outMat, lastColumnLut);
        }
        // Wake any LOADED neighbour column that could have received mass across an X/Z boundary this step
        // (DESIGN §5 wake-on-cross): if our column moved any mass, the apron neighbour must re-enter next
        // cycle to accept incoming flow rather than stranding it at the seam. Column-granular replacement
        // (the old per-section seam wake); over-waking is harmless (the neighbour settles via noteSettle).
        if (maxMassDelta >= SettleCountdown.EPS_MASS) {
            wakeColumnNeighbour(entry.dimension(), entry.cx() - 1, entry.cz());
            wakeColumnNeighbour(entry.dimension(), entry.cx() + 1, entry.cz());
            wakeColumnNeighbour(entry.dimension(), entry.cx(), entry.cz() - 1);
            wakeColumnNeighbour(entry.dimension(), entry.cx(), entry.cz() + 1);
        }
    }

    /** The section-Y slice of a column's input mass (for the per-section reconstructed StepTask). */
    private static float[] inMass(float[] columnInMass, int sectionY) {
        float[] m = new float[SectionData.CELLS];
        for (int z = 0; z < 16; z++) {
            for (int sy = 0; sy < 16; sy++) {
                int colRow = 16 * ColumnSectionCodec.engineY(sectionY, sy) + 6144 * z;
                int secRow = 16 * sy + 256 * z;
                for (int x = 0; x < 16; x++) {
                    m[secRow + x] = columnInMass[colRow + x];
                }
            }
        }
        return m;
    }

    /** Wake every loaded section of a neighbour column's flow pass so it re-enters next cycle. */
    private void wakeColumnNeighbour(Identifier dim, int cx, int cz) {
        // We don't know which sectionY received mass; wake the whole loaded column's flow countdowns so a
        // dormant neighbour re-enters. Cheap: one map touch per existing section; the codec drives the
        // common case where flow stays near the donor anyway.
        SectionStore store = stores.store(dim);
        if (store == null || !store.isLoaded(cx, cz)) return;
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
            if (store.hasSection(key)) {
                activeSet.wakeFlowSection(dim, key);
            }
        }
    }

    /** Max material defaultMass over the column's species (the per-cell mass clamp bound). */
    private static float fullMassBound(ColumnTask task) {
        // The column carries no LUT; the bound is the largest legal per-cell mass. Use a generous water-
        // scale cap (1000 kg is every fluid's full block); a higher-density fluid keeps its own mass via
        // the per-species ledger upstream. Clamp here only guards against engine NaN/overflow leakage.
        return Float.MAX_VALUE; // per-species bounds are enforced by the region ledger; avoid clamping real mass
    }

    private static float maxAbsDelta(float[] a, float[] b) {
        float m = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            float d = Math.abs(a[i] - b[i]);
            if (d > m) m = d;
        }
        return m;
    }

    private static long packColumn(int cx, int cz) {
        return (cx & 0xffffffffL) | (((long) cz) << 32);
    }
    private static int unpackCx(long packed) { return (int) (packed & 0xffffffffL); }
    private static int unpackCz(long packed) { return (int) (packed >> 32); }

    private void addLoadedNeighbour(ServerLevel level, Set<Long> columns, int cx, int cz) {
        if (LiveMaterials.loadedChunk(level, cx, cz) != null) {
            columns.add(packColumn(cx, cz));
        }
    }
}
