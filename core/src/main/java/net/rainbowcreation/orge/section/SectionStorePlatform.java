package net.rainbowcreation.orge.section;

import dev.architectury.injectables.annotations.ExpectPlatform;

/**
 * Loader-specific chunk load/unload hooks for the section store (DESIGN.md §5).
 *
 * <p>Architectury exposes no common chunk load/unload (activation) events, so each
 * loader's native chunk events are bridged here via {@link ExpectPlatform}:
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
