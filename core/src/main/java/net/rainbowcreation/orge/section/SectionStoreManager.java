package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-side, loader-agnostic owner of one {@link SectionStore} per dimension
 * (DESIGN.md §5). The loader layer (Architectury {@code LifecycleEvent} +
 * per-loader chunk events) translates {@code ServerLevel} into a dimension
 * {@link Identifier} and the level's save {@link Path} and drives this manager;
 * the manager itself never touches Minecraft world types beyond the vanilla
 * {@link Identifier} key.
 *
 * <p>Each dimension gets a {@link SectionStore} backed by a {@link RegionStore}
 * rooted at the level's save directory; the {@code RegionStore} creates an
 * {@code orge/} subdirectory there and never touches vanilla {@code .mca} files.</p>
 *
 * <p><strong>Single-thread confinement:</strong> all methods must be called on the
 * server thread (the same thread that fires chunk/level events). No internal
 * locking is provided — mirrors {@link SectionStore}'s contract.</p>
 */
public final class SectionStoreManager {

    /** Pairs a dimension's live {@link SectionStore} with the {@link RegionStore} it owns. */
    private record Holder(SectionStore store, RegionStore region) {
    }

    private final Map<Identifier, Holder> byDimension = new HashMap<>();

    /**
     * Registers a dimension's {@link SectionStore}, backed by a {@link RegionStore}
     * rooted at {@code levelDir}. <strong>Idempotent:</strong> if the dimension is
     * already registered, the existing live store is kept and this call is a no-op
     * (the in-memory state is never clobbered by a re-load).
     *
     * @param dim      dimension key; must not be {@code null}
     * @param levelDir the level's save directory (the {@code orge/} subdir is created under it); must not be {@code null}
     * @param ambient  ambient provider for never-simulated sections; must not be {@code null}
     */
    public void onLevelLoad(Identifier dim, Path levelDir, AmbientProvider ambient) {
        Objects.requireNonNull(dim, "dim");
        Objects.requireNonNull(levelDir, "levelDir");
        Objects.requireNonNull(ambient, "ambient");
        if (byDimension.containsKey(dim)) {
            return; // idempotent: keep the live store
        }
        RegionStore region = new RegionStore(levelDir);
        byDimension.put(dim, new Holder(new SectionStore(region, ambient), region));
    }

    /** Loads a chunk column into the dimension's store. No-op if the dimension is unknown. */
    public void onChunkLoad(Identifier dim, int cx, int cz) {
        Holder h = byDimension.get(dim);
        if (h != null) {
            h.store.loadColumn(cx, cz);
        }
    }

    /** Flushes (if dirty) and evicts a chunk column. No-op if the dimension is unknown. */
    public void onChunkUnload(Identifier dim, int cx, int cz) {
        Holder h = byDimension.get(dim);
        if (h != null) {
            h.store.unloadColumn(cx, cz);
        }
    }

    /** Flushes all dirty columns for a dimension (autosave). No-op if the dimension is unknown. */
    public void onLevelSave(Identifier dim) {
        Holder h = byDimension.get(dim);
        if (h != null) {
            h.store.flushAll();
        }
    }

    /**
     * Flushes all dirty columns, closes the dimension's region files, and removes the
     * dimension entry. No-op if the dimension is unknown.
     */
    public void onLevelUnload(Identifier dim) {
        Holder h = byDimension.remove(dim);
        if (h != null) {
            h.store.flushAll();
            h.region.closeAll();
        }
    }

    /**
     * Accessor for a dimension's live {@link SectionStore}.
     *
     * @return the store, or {@code null} if the dimension has not been loaded
     */
    public SectionStore store(Identifier dim) {
        Holder h = byDimension.get(dim);
        return h == null ? null : h.store;
    }
}
