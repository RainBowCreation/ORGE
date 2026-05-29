package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a block id to a {@link Material} id (DESIGN.md §6).
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Per-block <em>override</em> — exact match wins unconditionally.</li>
 *   <li><em>Tag bindings</em> in insertion order — first registered tag whose
 *       membership check returns {@code true} wins.</li>
 *   <li>Global fallback {@link MaterialRegistry#FALLBACK_ID}.</li>
 * </ol>
 *
 * <p>Tag membership is injected via {@link TagMembership} so this class remains
 * pure (no Minecraft world/registry access) and fully unit-testable.</p>
 */
public final class MaterialBindings {

    /**
     * Injected predicate for tag membership.  Callers supply a lambda backed by
     * whatever tag system is appropriate (datapack tags at runtime, an in-test map
     * during unit tests).
     */
    public interface TagMembership {
        /** Returns {@code true} if {@code blockId} is a member of {@code tagId}. */
        boolean contains(Identifier tagId, Identifier blockId);
    }

    /** Per-block overrides: blockId → materialId. */
    private final Map<Identifier, Identifier> overrides = new HashMap<>();

    /**
     * Tag bindings in registration order: tagId → materialId.
     * {@link LinkedHashMap} preserves insertion order so the first-registered
     * matching tag always wins.
     */
    private final LinkedHashMap<Identifier, Identifier> tagBindings = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Mutation API
    // -------------------------------------------------------------------------

    /** Registers an exact per-block binding. Replaces any previous override for the same block. */
    public void addOverride(Identifier blockId, Identifier materialId) {
        overrides.put(blockId, materialId);
    }

    /**
     * Registers a tag binding.  First-registered tag wins when multiple tags match
     * the same block (insertion order is preserved by {@link LinkedHashMap}).
     * Replaces the material of a previously registered binding for the same tag.
     */
    public void addTagBinding(Identifier tagId, Identifier materialId) {
        tagBindings.put(tagId, materialId);
    }

    /** Removes all overrides and tag bindings. */
    public void clear() {
        overrides.clear();
        tagBindings.clear();
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    /**
     * Returns the material id bound to {@code blockId}.
     *
     * <p>Resolution order: override → first matching tag (insertion order) → fallback.</p>
     *
     * @param blockId the block whose material is needed
     * @param tags    injected tag-membership predicate
     * @return the resolved material id; never {@code null}
     */
    public Identifier materialFor(Identifier blockId, TagMembership tags) {
        // 1. Per-block override
        Identifier override = overrides.get(blockId);
        if (override != null) {
            return override;
        }

        // 2. Tag bindings in insertion order — first match wins
        for (Map.Entry<Identifier, Identifier> entry : tagBindings.entrySet()) {
            if (tags.contains(entry.getKey(), blockId)) {
                return entry.getValue();
            }
        }

        // 3. Global fallback
        return MaterialRegistry.FALLBACK_ID;
    }
}
