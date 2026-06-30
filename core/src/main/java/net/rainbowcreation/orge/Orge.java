package net.rainbowcreation.orge;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import net.rainbowcreation.orge.command.CellSpeciesSource;
import net.rainbowcreation.orge.command.LiveReadoutManager;
import net.rainbowcreation.orge.command.LiveStatus;
import net.rainbowcreation.orge.command.OrgeCommandLogic;
import net.rainbowcreation.orge.command.OrgeCommands;
import net.rainbowcreation.orge.command.ReadRangeProvider;
import net.rainbowcreation.orge.command.SectionStatusSource;
import net.rainbowcreation.orge.command.ServerStoreReadSource;
import net.rainbowcreation.orge.command.ServerStoreWriteSink;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.fluid.VanillaFluidSuppressor;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialJsonLoader;
import net.rainbowcreation.orge.phase.MinecraftFluidReconciler;
import net.rainbowcreation.orge.phase.MinecraftPhaseChanger;
import net.rainbowcreation.orge.scheduler.ExecutorStepRunner;
import net.rainbowcreation.orge.scheduler.ActiveSet;
import net.rainbowcreation.orge.scheduler.CellMaterialTracker;
import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.scheduler.MinecraftThermalWorld;
import net.rainbowcreation.orge.scheduler.Scheduler;
import net.rainbowcreation.orge.scheduler.WakeSink;
import net.rainbowcreation.orge.scheduler.Worker;
import net.rainbowcreation.orge.platform.WakePlatform;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SectionStorePlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * ORGE — Overhauled Realistic General Elements.
 *
 * <p>Shared, loader-agnostic entry point. Both the Fabric and NeoForge entrypoints
 * call {@link #init()} exactly once. See {@code DESIGN.md} for the authoritative
 * architecture; this class is intentionally thin in the Phase 1 skeleton.</p>
 */
public final class Orge {

    public static final String MOD_ID = "orge";

    public static final Logger LOGGER = LoggerFactory.getLogger("ORGE");

    /**
     * Shared, server-thread-confined owner of the per-dimension section stores
     * (DESIGN.md §5). The loader chunk/level hooks and any future subsystem
     * (scheduler, engine) read and write through this single instance.
     */
    public static final SectionStoreManager SECTION_STORES = new SectionStoreManager();

    /** DESIGN §8 — the single-node conduction scheduler and its background runner. */
    private static ExecutorStepRunner stepRunner;
    private static MinecraftThermalWorld thermalWorld;
    private static Scheduler scheduler;
    private static MinecraftPhaseChanger phaseChanger;
    private static MinecraftFluidReconciler fluidReconciler;

    private static boolean initialized = false;

    private Orge() {
    }

    /**
     * Per-dimension save directory under which the {@code orge/} region store is rooted.
     *
     * <p>Uses vanilla {@link DimensionType#getStorageFolder(net.minecraft.resources.ResourceKey, Path)}:
     * the overworld resolves to the world root and other dimensions to their own
     * {@code dimensions/<ns>/<path>/} folder — exactly where vanilla keeps each
     * dimension's {@code region/} {@code .mca} files, so ORGE data lives beside (never
     * inside) them. We only ever create/write the {@code orge/} subdirectory there.</p>
     */
    private static Path levelDirOf(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), worldRoot);
    }

    /** Wire up the loader-agnostic subsystems. Idempotent. */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        boolean nativeOk;
        try {
            net.rainbowcreation.orge.engine.NativeLoader.load();
            nativeOk = true;
        } catch (Throwable t) {
            nativeOk = false;
        }
        LOGGER.info("============================================================");
        LOGGER.info("[ORGE] {}", BuildInfo.banner());
        LOGGER.info("[ORGE] native liborge loaded = {}", nativeOk);
        LOGGER.info("============================================================");
        LOGGER.info("ORGE v2 thermal core initializing (Phase 1 skeleton).");

        // ORGE registers NO blocks: every substance is a material, and a material renders via its
        // representative_block (steam → minecraft:air, an invisible gas). Identity is durable per-cell
        // in §5 SectionStore, so no block is needed to carry species.

        // DESIGN.md §6 — material model: register the datapack reload listener so
        // materials load at server start and refresh on /reload. One
        // Architectury registration covers both Fabric and NeoForge.
        ReloadListenerRegistry.register(
                PackType.SERVER_DATA,
                new MaterialJsonLoader(),
                Identifier.fromNamespaceAndPath(MOD_ID, "materials"));

        // DESIGN.md §5 — section store: wire chunk + level lifecycle to the per-dimension
        // SectionStore so ORGE data persists under each level's orge/ dir (never .mca).
        //
        // Level load/save/unload use Architectury's common LifecycleEvent (one
        // registration, both loaders). Chunk load/unload have no Architectury common
        // event, so they go through the SectionStorePlatform @ExpectPlatform seam,
        // implemented per loader with the loader-native chunk events.
        //
        // TODO(phase: section-store): use AmbientProvider.FALLBACK (~285 K, 0 mass) for
        // now. The real biome-temperature + material-defaultMass provider is deferred
        // until its consumers (engine/scheduler/phase-change) exist.
        LifecycleEvent.SERVER_LEVEL_LOAD.register(level ->
                SECTION_STORES.onLevelLoad(
                        level.dimension().identifier(),
                        levelDirOf(level),
                        AmbientProvider.FALLBACK));
        LifecycleEvent.SERVER_LEVEL_SAVE.register(level ->
                SECTION_STORES.onLevelSave(level.dimension().identifier()));
        LifecycleEvent.SERVER_LEVEL_UNLOAD.register(level ->
                SECTION_STORES.onLevelUnload(level.dimension().identifier()));

        SectionStorePlatform.registerChunkHooks(SECTION_STORES);

        // DESIGN §10 Decision 8 — make ORGE the sole authority over its managed water/lava by
        // suppressing vanilla liquid physics for them. Each loader's FlowingFluid#tick mixin cancels
        // the vanilla flow/spread tick for ORGE-managed cells; this wires the managed-section hook the
        // mixin's policy needs from SECTION_STORES. The lava↔water → obsidian/cobblestone/basalt path
        // is preserved (policy never suppresses next to an interacting fluid, and that path lives in
        // LiquidBlock, not FlowingFluid#tick).
        VanillaFluidSuppressor.install(SECTION_STORES);

        // DESIGN.md §8 — scheduler: one fallback engine on a background thread, driven once per
        // real second from the common server-tick event. Single-node v1 (the server is the
        // sole worker); client-distributed workers + the wire protocol are a follow-on track.
        OrgeEngine engine = EngineFactory.create();
        stepRunner = new ExecutorStepRunner();
        // §10 follow-on: remembers each simulated cell's material so the snapshot can re-seed a cell
        // whose block changed (bucket fluid, /setblock) — the §5 store keeps no material. Pruned per
        // column on chunk unload so it tracks only loaded sections.
        CellMaterialTracker cellMaterials = new CellMaterialTracker();
        ActiveSet activeSet = new ActiveSet();
        SECTION_STORES.setColumnUnloadListener((dim, cx, cz) -> {
            cellMaterials.forgetColumn(dim, cx, cz);
            activeSet.forgetColumn(dim, cx, cz);
            // Bound the placement queue too: drop any pending intents for the unloaded column.
            thermalWorld.pendingInjections().forgetColumn(dim, cx, cz);
        });
        thermalWorld = new MinecraftThermalWorld(SECTION_STORES, cellMaterials, activeSet);
        phaseChanger = new MinecraftPhaseChanger(SECTION_STORES);
        fluidReconciler = new MinecraftFluidReconciler(SECTION_STORES);
        // Whole-region column write-back drives the §7 phase-changer + §10 reconciler per section.
        thermalWorld.setReconcilers(fluidReconciler, phaseChanger);
        Worker serverWorker = new Worker(
                UUID.randomUUID(), true,
                Scheduler.DEFAULT_RANGE, Scheduler.MAX_RANGE,
                Scheduler.COMPUTE_BUDGET_MILLIS, Scheduler.ON_TIME_TICKS_TO_CLIMB);
        scheduler = new Scheduler(engine, thermalWorld, stepRunner, serverWorker, phaseChanger, fluidReconciler);

        // §10 Decision 11 trigger (a): wake the owning section on any player block edit or bucket use.
        // BlockEvent.PLACE/BREAK + PlayerEvent.FILL_BUCKET are COMMON Architectury events (one
        // registration, both loaders). WakePlatform (ExpectPlatform) adds the low-level setBlock path
        // (/setblock, pistons, programmatic edits) the common events don't cover. Server-side only;
        // over-waking is harmless (the section just sees one extra settle step). Once a section is
        // dormant it is no longer snapshotted, so the snapshot-diff cannot notice an edit — wake MUST
        // be explicit, or a bucket-placed fluid sits frozen forever (a missed trigger = stale fluid).
        WakeSink wake = thermalWorld.wakeSink();
        BlockEvent.PLACE.register((level, pos, state, placer) -> {
            if (net.rainbowcreation.orge.scheduler.InjectDebug.on()
                    && net.rainbowcreation.orge.scheduler.InjectDebug.throttle(
                            "common-place-" + (level instanceof ServerLevel), 500)) {
                net.rainbowcreation.orge.scheduler.InjectDebug.LOG.info(
                        "[common-event] BlockEvent.PLACE fired serverLevel={} thread={} at ({},{},{})",
                        level instanceof ServerLevel, Thread.currentThread().getName(),
                        pos.getX(), pos.getY(), pos.getZ());
            }
            if (level instanceof ServerLevel sl) {
                wake.wakeBlock(sl.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
            }
            return EventResult.pass();
        });
        BlockEvent.BREAK.register((level, pos, state, player, xp) -> {
            if (net.rainbowcreation.orge.scheduler.InjectDebug.on()
                    && net.rainbowcreation.orge.scheduler.InjectDebug.throttle(
                            "common-break-" + (level instanceof ServerLevel), 500)) {
                net.rainbowcreation.orge.scheduler.InjectDebug.LOG.info(
                        "[common-event] BlockEvent.BREAK fired serverLevel={} thread={} at ({},{},{})",
                        level instanceof ServerLevel, Thread.currentThread().getName(),
                        pos.getX(), pos.getY(), pos.getZ());
            }
            if (level instanceof ServerLevel sl) {
                // BREAK → durable vacuum (spec durable-material Part 4): wakeBreak does NOT read the
                // (still-outgoing, pre-event) block; it enqueues a removal→vacuum intent. PLACE/FILL_BUCKET
                // stay on wakeBlock (they read the live placed block).
                wake.wakeBreak(sl.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
            }
            return EventResult.pass();
        });
        PlayerEvent.FILL_BUCKET.register((player, level, stack, target) -> {
            if (net.rainbowcreation.orge.scheduler.InjectDebug.on()
                    && net.rainbowcreation.orge.scheduler.InjectDebug.throttle(
                            "common-bucket-" + (level instanceof ServerLevel), 500)) {
                net.rainbowcreation.orge.scheduler.InjectDebug.LOG.info(
                        "[common-event] PlayerEvent.FILL_BUCKET fired serverLevel={} thread={} targetType={}",
                        level instanceof ServerLevel, Thread.currentThread().getName(),
                        target == null ? "null" : target.getType());
            }
            if (level instanceof ServerLevel sl && target instanceof BlockHitResult hit) {
                BlockPos p = hit.getBlockPos();
                wake.wakeBlock(sl.dimension().identifier(), p.getX(), p.getY(), p.getZ());
            }
            return InteractionResult.PASS;
        });
        // trigger (a), low-level: /setblock, datapack/command edits, pistons, dispenser/bucket
        // placements, and the reconciler's own air→fluid writes (no Architectury common event).
        WakePlatform.registerBlockChangeWake(wake);

        // DESIGN §7 — phase change reacts to the temps the scheduler writes back each second.
        LifecycleEvent.SERVER_STARTED.register(server -> {
            thermalWorld.bindServer(server);
            phaseChanger.bindServer(server);
            fluidReconciler.bindServer(server);
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            thermalWorld.unbindServer();
            phaseChanger.unbindServer();
            fluidReconciler.unbindServer();
            cellMaterials.clear();
            activeSet.clear();
            stepRunner.shutdown();
        });
        // Bind the conduction clock to Minecraft's game-tick clock: skip stepping while the world
        // is frozen (/tick freeze), and let the 20-tick cadence ride /tick rate (slow/sprint).
        TickEvent.SERVER_POST.register(server ->
                scheduler.onServerTick(server.tickRateManager().runsNormally()));

        // DESIGN observability track (Topic A): /orge get|section|set|fill. Reads walk a
        // source chain (client cache -> server fallback; v1 = server only); writes are
        // server-authoritative and op-gated. One common Architectury event covers both loaders.
        // §10 Decision 11 trigger (b), belt-and-suspenders: a /orge set/fill writes a temp/mass into
        // a cell without a block change, so the source roster (derived fresh from blocks) wouldn't see
        // it. Pass the wake sink so a temp write wakes the thermal pass and a mass write wakes flow.
        // A /orge set/fill onto a never-simulated cell hits a synthesized vacuum stub; this resolver
        // gives that cell its LIVE block species (the same block first-touch identity the snapshot uses)
        // so the temperature encode lands on the right enthalpy curve instead of storing 0 J and losing
        // the edit. Server-thread only; returns null (sink leaves identity untouched) when unresolvable.
        CellSpeciesSource liveSpecies = (dim, key, cell) -> {
            MinecraftServer srv = thermalWorld.boundServer();
            if (srv == null) {
                return null;
            }
            ServerLevel level = null;
            for (ServerLevel l : srv.getAllLevels()) {
                if (l.dimension().identifier().equals(dim)) {
                    level = l;
                    break;
                }
            }
            if (level == null) {
                return null;
            }
            LevelChunk chunk = LiveMaterials.loadedChunk(level, key.cx(), key.cz());
            if (chunk == null) {
                return null;
            }
            LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, key.sectionY());
            if (section == null) {
                return null;
            }
            Material m = LiveMaterials.materialFor(
                    LiveMaterials.blockAt(section, cell), ActiveMaterials.current().registry());
            return m == null ? null : m.id();
        };
        OrgeCommandLogic commandLogic = new OrgeCommandLogic(
                List.of(new ServerStoreReadSource(SECTION_STORES)),
                new ServerStoreWriteSink(SECTION_STORES, wake, liveSpecies),
                (ReadRangeProvider) () -> Scheduler.MAX_RANGE);
        // get-live status: UNLOADED (column gone) -> AMBIENT (loaded, never simulated) -> DORMANT
        // (settled, dropped from schedule) / ACTIVE (stepping). Closes over the store + active set,
        // which the command layer has no other handle on. Server-thread only (get-live ticks there).
        SectionStatusSource statusSource = (dim, key) -> {
            net.rainbowcreation.orge.section.SectionStore store = SECTION_STORES.store(dim);
            if (store == null || !store.isLoaded(key.cx(), key.cz())) {
                return LiveStatus.UNLOADED;
            }
            if (!store.hasSection(key)) {
                return LiveStatus.AMBIENT;
            }
            return activeSet.isAsleep(dim, key) ? LiveStatus.DORMANT : LiveStatus.ACTIVE;
        };
        LiveReadoutManager liveReadout = new LiveReadoutManager(commandLogic, statusSource);
        OrgeCommands orgeCommands = new OrgeCommands(commandLogic, liveReadout);
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) ->
                orgeCommands.register(dispatcher));
        // /orge get-live paints each toggled player's crosshair cell to the action bar every tick.
        TickEvent.SERVER_POST.register(liveReadout::tick);
        LifecycleEvent.SERVER_STOPPING.register(server -> liveReadout.clear());
    }
}
