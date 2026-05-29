package net.rainbowcreation.orge.section;

import dev.architectury.injectables.annotations.ExpectPlatform;

/**
 * Loader-specific chunk load/unload hooks for the section store (DESIGN.md §5).
 *
 * <p>Architectury's common {@code LifecycleEvent} covers level load/save/unload on
 * both loaders, and {@code ChunkEvent.LOAD_DATA} covers chunk load — but Architectury
 * exposes <em>no</em> chunk-unload event. To keep both chunk edges consistent we route
 * chunk load <em>and</em> unload through this {@link ExpectPlatform} seam, implemented
 * with the loader-native events:
 * <ul>
 *   <li>Fabric: {@code ServerChunkEvents.CHUNK_LOAD} / {@code CHUNK_UNLOAD}
 *       ({@code (ServerLevel, LevelChunk)}).</li>
 *   <li>NeoForge: {@code ChunkEvent.Load} / {@code ChunkEvent.Unload} on
 *       {@code NeoForge.EVENT_BUS}, guarded server-side via {@code !getLevel().isClientSide()}.</li>
 * </ul>
 *
 * <p>Each impl derives the dimension {@link net.minecraft.resources.Identifier} from
 * {@code serverLevel.dimension().identifier()} and forwards the chunk coords to the
 * shared {@link SectionStoreManager}. Server-side only — client levels are ignored,
 * as ORGE state is server-authoritative.</p>
 */
public final class SectionStorePlatform {

    private SectionStorePlatform() {
    }

    /**
     * Registers the per-loader chunk load/unload listeners that drive {@code manager}.
     * Called once from {@code Orge.init()}.
     *
     * @param manager the shared, server-thread-confined section store manager
     */
    @ExpectPlatform
    public static void registerChunkHooks(SectionStoreManager manager) {
        throw new AssertionError("ExpectPlatform implementation not found");
    }
}
