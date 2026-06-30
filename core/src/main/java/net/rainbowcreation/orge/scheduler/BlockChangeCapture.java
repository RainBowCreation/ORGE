package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.function.Supplier;

/**
 * World-event ingest for the live {@link MinecraftThermalWorld}: a wake event in &rarr; a
 * {@link SectionStore}/{@link PendingInjections} write out. Owns the {@link WakeSink} the loader
 * hooks push into, the per-block placement/break capture (durable identity + a displacement-or-removal
 * intent under the unified substance model), the shared removal record, the capture-decision trace,
 * and the biome ambient helper {@link ColumnSnapshot} also reads.
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
     *  chunk not loaded, prior null) so it is inert in the headless suites where the server is unbound.
     *  Shared wake path: PLACE/BREAK/FILL_BUCKET all route here; the event-driven break→vacuum path is
     *  introduced separately (Task G2) — this method records the live (post-event) block as-is. */
    private void captureBlockChange(Identifier dim, int blockX, int blockY, int blockZ) {
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
