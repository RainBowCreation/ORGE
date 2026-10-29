package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.BlockMaterialRule;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * World-event ingest for the live {@link MinecraftThermalWorld}: a wake event in &rarr; a
 * {@link SectionStore}/{@link PendingInjections} write out. Owns the {@link WakeSink} the loader
 * hooks push into, the per-block placement/break capture (durable identity + a displacement-or-removal
 * intent under the unified substance model), the displacement policy ({@link #isDisplacement}) and the
 * pure capture step ({@link #capturePlacement} / {@link #captureOrCancelStaleRemoval}) folded in behind
 * this surface, the shared removal record, the capture-decision trace, and the biome ambient helper
 * {@link ColumnSnapshot} also reads.
 *
 * <p>Server-thread-confined; the live {@link MinecraftServer} is read lazily through the injected
 * {@code serverSource}. Fully null/guard-safe (server unbound, level absent, chunk unloaded, prior
 * null) so the capture entry points are inert in the headless suites where the server is unbound,
 * while {@link #captureBreak} and {@link #recordDurableIdentity} stay directly drivable against the
 * real §5 store.</p>
 */
final class BlockChangeCapture {

    /** First-touch material of empty/ambient air. An EDIT that leaves a SIMULATED cell as orge:air is a
     *  REMOVAL → durable vacuum (spec durable-material Part 4): the substance left, so the cell becomes the
     *  index-0 sentinel, NOT a fabricated 1.2 kg air block. (Ambient air is only ever seeded by the
     *  assembler's first-touch on NEVER-SIMULATED cells.) */
    private static final Identifier AIR_ID = Identifier.fromNamespaceAndPath("orge", "air");

    private final SectionStoreManager stores;
    private final CellMaterialTracker cellMaterials;
    private final ActiveSet activeSet;
    private final PendingInjections pendingInjections;
    private final Supplier<MinecraftServer> serverSource;

    BlockChangeCapture(SectionStoreManager stores, CellMaterialTracker cellMaterials,
                       ActiveSet activeSet, PendingInjections pendingInjections,
                       Supplier<MinecraftServer> serverSource) {
        this.stores = stores;
        this.cellMaterials = cellMaterials;
        this.activeSet = activeSet;
        this.pendingInjections = pendingInjections;
        this.serverSource = serverSource;
    }

    /** The wake sink the loader event hooks push into (DESIGN §10 Decision 11). The composite runs the
     *  placement-injection capture first, then delegates the unchanged wake behaviour to {@link ActiveSet}. */
    WakeSink wakeSink() {
        return capturingWakeSink;
    }

    /** A section of a dimension. */
    private record DimSection(Identifier dim, SubchunkKey key) {}

    /** Above this many distinct changed sections the log stops listing them and reports every section changed. */
    static final int MAX_LOGGED_SECTIONS = 4096;

    /** Sections a block changed in since the last {@link #markCaptured} — by anything but ORGE's own writes
     *  (issue #51: the write-back's block plans were made from that capture, so they are stale there). */
    private final Set<DimSection> changedSinceCapture = new HashSet<>();
    private boolean changeLogOverflow;

    /** A cycle's capture was just taken: start a fresh change log. */
    void markCaptured() {
        changedSinceCapture.clear();
        changeLogOverflow = false;
    }

    /** Whether a block of {@code key} changed since the last capture (not by ORGE's own writes). */
    boolean changedSinceCapture(Identifier dim, SubchunkKey key) {
        return changeLogOverflow || changedSinceCapture.contains(new DimSection(dim, key));
    }

    private void logChange(Identifier dim, int blockX, int blockY, int blockZ) {
        if (LiveMaterials.inSelfWrite() || changeLogOverflow) {
            return;
        }
        changedSinceCapture.add(new DimSection(dim, new SubchunkKey(SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockY), SectionPos.blockToSectionCoord(blockZ))));
        if (changedSinceCapture.size() > MAX_LOGGED_SECTIONS) {
            changedSinceCapture.clear();
            changeLogOverflow = true;
        }
    }

    private final WakeSink capturingWakeSink = new WakeSink() {
        @Override
        public void wakeBlock(Identifier dim, int blockX, int blockY, int blockZ) {
            logChange(dim, blockX, blockY, blockZ);
            captureBlockChange(dim, blockX, blockY, blockZ);    // enqueue intent if a displacement placement
            activeSet.wakeBlock(dim, blockX, blockY, blockZ);   // unchanged wake behaviour
        }

        @Override
        public void wakeBreak(Identifier dim, int blockX, int blockY, int blockZ) {
            logChange(dim, blockX, blockY, blockZ);
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
     *  ({@link #isDisplacement} — solid or fluid alike captures the placed material's
     *  {@code defaultMass} and displaces the incumbent), and (b) record the placed block's first-touch
     *  material as the cell's DURABLE identity in the {@link SectionStore} (spec Part 3), so the
     *  placement persists against the next assemble even before the engine writes it back (the vanish
     *  race, now fixed for every species). The reconciler's own steady-state repaint (live == recorded
     *  incumbent) is still filtered out by the policy. Fully null/guard-safe (server null, level null,
     *  chunk not loaded, prior null) so it is inert in the headless suites where the server is unbound.
     *  Shared wake path: PLACE/BREAK/FILL_BUCKET all route here; the event-driven break→vacuum path is
     *  introduced separately (Task G2) — this method records the live (post-event) block as-is. */
    private void captureBlockChange(Identifier dim, int blockX, int blockY, int blockZ) {
        if (LiveMaterials.inSelfWrite()) {
            return; // ORGE's own repaint of an engine species — never a player placement/removal
        }
        MinecraftServer srv = serverSource.get();
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

        // Live material from the world block (null for a transient carrier — the policy null-guards it).
        BlockPos pos = new BlockPos(blockX, blockY, blockZ);
        Material live = placedMaterial(
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()), mats.registry());

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

        // A piston's moving_piston carrier is no placement (issue #63): the cell keeps its incumbent and
        // durable identity until the carried block lands, and that landing's own wake is the capture.
        if (live == null) {
            return;
        }

        // Override-close (spec Part 4): an EDIT whose first-touch material is orge:air at a SIMULATED cell is
        // a REMOVAL → durable vacuum, NOT an air placement. (The substance left; ambient air is only ever
        // seeded by the assembler's first-touch on never-simulated cells.) This makes the explicit BREAK
        // event AND the post-break setBlock-to-air agree on vacuum, so the air write can no longer resurrect
        // a broken cell. A steady-state air↔air / air→vacuum repaint stays a NO-OP: if the recorded incumbent
        // is already air or vacuum, there is nothing to remove (don't enqueue a removal every reconcile cycle).
        if (live != null && AIR_ID.equals(live.id())) {
            Identifier incId = incumbent != null ? incumbent.id() : null;
            if (airEditRemoves(pendingInjections, dim, cx, cz, engineCell, incId)) {
                recordRemoval(stores.store(dim), dim, cx, cz, sectionY, sectionCell, engineCell);
            }
            return; // never enqueue an air PLACEMENT or record an air identity at a simulated cell
        }

        captureEdit(pendingInjections, stores.store(dim), dim, cx, cz, sectionY, sectionCell, engineCell,
                live, incumbent, isVanillaSpread(level.getFluidState(pos)), ambientK);
    }

    /** Whether an edit to air at a simulated cell removes something: the recorded incumbent is not already
     *  empty, or a placement is still queued there. The recorded incumbent is the last engine output and lags
     *  a queued placement, so `fill stone` then `fill air` must still remove the stone. */
    static boolean airEditRemoves(PendingInjections q, Identifier dim, int cx, int cz, int engineCell,
                                  Identifier incumbentId) {
        boolean recordedEmpty = incumbentId == null
                || AIR_ID.equals(incumbentId) || MaterialPalette.VACUUM_ID.equals(incumbentId);
        return !recordedEmpty || q.hasPendingPlacement(dim, cx, cz, engineCell);
    }

    /** A non-source fluid state is vanilla spread (issue #64): vanilla flow ticks in an unmanaged section
     *  and only ever writes flowing/falling states (infinite water is disabled globally), while a bucket
     *  or a player places a source. */
    static boolean isVanillaSpread(FluidState fluid) {
        return !fluid.isEmpty() && !fluid.isSource();
    }

    /**
     * The pure tail of {@link #captureBlockChange} for a non-air live block: enqueue the displacement and
     * record the cell's durable identity — unless the edit is vanilla spread.
     *
     * <p>VANILLA SPREAD IS NOT A PLACEMENT (issue #64). Vanilla flow is suppressed only in a managed section,
     * so in an unmanaged one (and from it into a managed neighbour) it still writes flowing water, and each
     * write reaches this capture through the {@code Level#setBlock} wake. The cell is untracked there, so
     * {@link #isDisplacement} would enqueue the placed species' whole {@code defaultMass} — 1000 kg of water
     * credited per spread cell, while the source keeps its own — and the durable-identity write would
     * materialize the section, which the fluid policy then reads as managed and freezes un-simulated. A
     * spread edit therefore enqueues nothing and stores nothing: the substance stays in the source, and a
     * never-simulated section is taken in from its live blocks when it is first assembled.</p>
     *
     * @param vanillaSpread the live block's fluid is a non-source state ({@link #isVanillaSpread})
     */
    static void captureEdit(PendingInjections queue, SectionStore store, Identifier dim, int cx, int cz,
                            int sectionY, int sectionCell, int engineCell, Material live, Material incumbent,
                            boolean vanillaSpread, float ambientK) {
        if (vanillaSpread) {
            return;
        }
        // Same-window break/flicker + same-species re-place: cancel a stale pending removal instead of
        // letting it stomp the cell to vacuum (solid flow-through) or force-injecting a fresh defaultMass
        // (movable-fluid mass fabrication). Any other case delegates to the ordinary displacement capture.
        captureOrCancelStaleRemoval(queue, dim, cx, cz, engineCell, live, incumbent, ambientK,
                storedMass(store, new SubchunkKey(cx, sectionY, cz), sectionCell));

        // Durable identity (spec Part 3): the placed block's first-touch material becomes the cell's stored
        // material at once, so the placement persists even before the engine writes it back (vanish-race fix,
        // generalised to every species). live == firstTouchMaterial(placedBlock) already (computed above).
        recordDurableIdentity(store, cx, cz, sectionY, sectionCell, live);
    }

    /**
     * The material a block change at a cell captures: the placed block's first-touch material, or
     * {@code null} for a transient carrier ({@link BlockMaterialRule#isTransient}) — a piston's
     * {@code moving_piston} would otherwise first-touch to {@code orge:generic_solid} and inject it.
     */
    static Material placedMaterial(Identifier blockId, MaterialRegistry registry) {
        return BlockMaterialRule.isTransient(blockId) ? null : BlockMaterialRule.firstTouchMaterial(blockId, registry);
    }

    /**
     * Displacement decision for the placement-injection capture (folded from the former
     * {@code PlacementInjectionPolicy}). A placement is captured (enqueued for displace-and-inject) when
     * the placed material differs from the recorded incumbent species, or the cell is untracked:
     * <ul>
     *   <li>the cell is untracked ({@code incumbent == null}) — enqueue so the placement is made durable
     *       against the stale-write-back vanish race;</li>
     *   <li>OR the placed species id differs from the incumbent species id — the new block replaces
     *       whatever was there (fluid, solid, or anything else).</li>
     * </ul>
     * Not captured: {@code live == null} (no ORGE material for the placed block), or a self-write where
     * {@code live.id()} equals {@code incumbent.id()} (the reconciler's own engine-output repaint).
     * {@code movable()} is NOT consulted — it governs only post-placement flow, not capture.
     *
     * @param live      material of the newly-placed block at the cell (from the live world).
     * @param incumbent material of the cell's recorded engine-output species (the thing to displace),
     *                  or {@code null} if the cell is untracked.
     */
    static boolean isDisplacement(Material live, Material incumbent) {
        if (live == null) return false;
        if (incumbent == null) return true;           // untracked cell — enqueue for durability
        return !live.id().equals(incumbent.id());     // different species → displace-and-inject
    }

    /**
     * Pure capture step for placement injection (folded from the former {@code PlacementCapture#capture}).
     * Given the live placed material and the recorded incumbent material at a cell, enqueue a placement
     * intent iff it is a displacement ({@link #isDisplacement}). The intent carries the NEW species' id +
     * {@code defaultMass} seed + seed temperature (material default, else biome ambient) — the same values
     * {@code ColumnAssembler} would have used, now owned by the engine.
     */
    static void capturePlacement(PendingInjections queue, Identifier dim, int cx, int cz, int cell,
                                 Material live, Material incumbent, float biomeAmbientK) {
        if (!isDisplacement(live, incumbent)) {
            return;
        }
        queue.enqueue(dim, cx, cz, cell, live.id(), live.defaultMass(), seedTemperature(live, biomeAmbientK));
    }

    /**
     * Removal-aware capture entry point used by the live reconciler (folded from the former
     * {@code PlacementCapture#captureOrCancelStaleRemoval}). When the cell has a same-window removal pending
     * AND the placed species equals the recorded incumbent (a re-place of the SAME thing — a player
     * break+replace, OR a fluid re-asserting over a transient air-level edit that the air-override turned
     * into a removal), the removal is STALE: cancel it and enqueue nothing. Cancelling — rather than letting
     * the lone removal stand or force-injecting the species — is the fix for BOTH failure modes:
     * <ul>
     *   <li>letting the removal stand stomps a solid's engine cell to vacuum under a still-solid durable
     *       identity → neighbours flow THROUGH the phantom hole;</li>
     *   <li>force-injecting the placement (a fresh {@code defaultMass}) FABRICATES mass for a movable fluid
     *       every time it re-asserts over a transient removal (the mass-doubling regression).</li>
     * </ul>
     * The cancelled cell keeps its durable identity + stored mass; no injection is emitted. Any OTHER case
     * (different species = a genuine displacement, or no pending removal) delegates unchanged to
     * {@link #capturePlacement}.
     *
     * <p>The cancel records a revival placement (issue #65): when the removal was already drained into
     * the in-flight step and that step is written back, the step's vacuum lands under the re-placed block,
     * so the revival places it again next cycle. It carries {@code storedMass} — the cell's mass before
     * the stomp, so the block comes back as it was — or the placed material's {@code defaultMass} when
     * that is unknown ({@code NaN}) or empty. A cancelled, held or failed step writes nothing and the
     * revival is never used.</p>
     */
    static void captureOrCancelStaleRemoval(PendingInjections queue, Identifier dim, int cx, int cz,
                                            int cell, Material live, Material incumbent,
                                            float biomeAmbientK) {
        captureOrCancelStaleRemoval(queue, dim, cx, cz, cell, live, incumbent, biomeAmbientK, Float.NaN);
    }

    static void captureOrCancelStaleRemoval(PendingInjections queue, Identifier dim, int cx, int cz,
                                            int cell, Material live, Material incumbent,
                                            float biomeAmbientK, float storedMass) {
        if (live != null && incumbent != null && live.id().equals(incumbent.id())
                && queue.hasPendingRemoval(dim, cx, cz, cell)) {
            float mass = storedMass > 0f ? storedMass : live.defaultMass();
            queue.cancelRemoval(dim, cx, cz, cell, new PendingInjections.Intent(
                    dim, cx, cz, cell, live.id(), mass, seedTemperature(live, biomeAmbientK)));
            return;
        }
        capturePlacement(queue, dim, cx, cz, cell, live, incumbent, biomeAmbientK);
    }

    /** The placed material's seed temperature: its default, else the biome ambient. */
    private static float seedTemperature(Material live, float biomeAmbientK) {
        return live.hasDefaultTemperature() ? live.defaultTemperature() : biomeAmbientK;
    }

    /** The cell's stored slot-0 mass, or {@code NaN} when its section is not in a loaded store. */
    static float storedMass(SectionStore store, SubchunkKey key, int sectionCell) {
        if (store == null || !store.isLoaded(key.cx(), key.cz()) || !store.hasSection(key)) {
            return Float.NaN;
        }
        return store.get(key).massAt(sectionCell);
    }

    /** Persist the placed block's first-touch material as the cell's durable {@link SectionStore} identity
     *  (spec Part 3), so the placement survives the next assemble before the engine writes it back. No-op
     *  when the block carries no ORGE material ({@code live == null}) or the column is absent/unloaded —
     *  guarding the store so a non-ORGE placement or an off-store cell is silently skipped. Package-visible
     *  so the headless suite can drive it against the real §5 store (the enclosing {@link #captureBlockChange}
     *  bails when the server is unbound and can't be driven there). */
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
     * event ({@link #captureBreak}) and the {@link #captureBlockChange} air-edit override route here, so a
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
        if (isDisplacement(live, incumbent)) {
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

    /** Biome base temperature sampled once at the section centre, mapped to Kelvin. Shared with
     *  {@link ColumnSnapshot}'s per-section assembly (the same ambient seed feeds capture + snapshot). */
    static float biomeAmbientK(ServerLevel level, SubchunkKey key) {
        int bx = (key.cx() << 4) + 8;
        int by = (key.sectionY() << 4) + 8;
        int bz = (key.cz() << 4) + 8;
        float base = level.getBiome(new BlockPos(bx, by, bz)).value().getBaseTemperature();
        return BiomeTemperature.toKelvin(base);
    }
}
