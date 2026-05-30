package net.rainbowcreation.orge;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.rainbowcreation.orge.block.ModBlocks;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.material.MaterialJsonLoader;
import net.rainbowcreation.orge.phase.MinecraftPhaseChanger;
import net.rainbowcreation.orge.scheduler.ExecutorStepRunner;
import net.rainbowcreation.orge.scheduler.MinecraftThermalWorld;
import net.rainbowcreation.orge.scheduler.Scheduler;
import net.rainbowcreation.orge.scheduler.Worker;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SectionStorePlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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

        LOGGER.info("ORGE v2 thermal core initializing (Phase 1 skeleton).");

        // DESIGN §7 — register ORGE's blocks (the inert orge:steam gas marker). Must run
        // during mod init, before any world loads.
        ModBlocks.register();

        // DESIGN.md §6 — material model: register the datapack reload listener so
        // materials + bindings load at server start and refresh on /reload. One
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
        // until its consumers (engine/scheduler/phase-change) exist — mirrors §6
        // deferring the live TagMembership bridge.
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

        // DESIGN.md §8 — scheduler: one fallback engine on a background thread, driven once per
        // real second from the common server-tick event. Single-node v1 (the server is the
        // sole worker); client-distributed workers + the wire protocol are a follow-on track.
        OrgeEngine engine = EngineFactory.create();
        stepRunner = new ExecutorStepRunner();
        thermalWorld = new MinecraftThermalWorld(SECTION_STORES);
        phaseChanger = new MinecraftPhaseChanger(SECTION_STORES);
        Worker serverWorker = new Worker(
                UUID.randomUUID(), true,
                Scheduler.DEFAULT_RANGE, Scheduler.MAX_RANGE,
                Scheduler.COMPUTE_BUDGET_MILLIS, Scheduler.ON_TIME_TICKS_TO_CLIMB);
        scheduler = new Scheduler(engine, thermalWorld, stepRunner, serverWorker, phaseChanger);

        // DESIGN §7 — phase change reacts to the temps the scheduler writes back each second.
        LifecycleEvent.SERVER_STARTED.register(server -> {
            thermalWorld.bindServer(server);
            phaseChanger.bindServer(server);
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            thermalWorld.unbindServer();
            phaseChanger.unbindServer();
            stepRunner.shutdown();
        });
        TickEvent.SERVER_POST.register(server -> scheduler.onServerTick());
    }
}
