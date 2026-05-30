package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a block id to a {@link Material} id (DESIGN.md §6).
 *
 * <p>Resolution order (see {@link #materialFor(Identifier, PropertyView, TagMembership)}):
 * <ol>
 *   <li><em>Predicated overrides</em> — per-block entries with blockstate requirements,
 *       in registration order; first entry whose requirements all match wins.</li>
 *   <li><em>Plain per-block override</em> — exact block-id match, unconditional.</li>
 *   <li><em>Tag bindings</em> in registration order — the tag must contain the block
 *       and any predicate must hold; first match wins.</li>
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
    @FunctionalInterface
    public interface TagMembership {
        /** Returns {@code true} if {@code blockId} is a member of {@code tagId}. */
        boolean contains(Identifier tagId, Identifier blockId);
    }

    /** Per-block plain overrides: blockId → materialId. */
    private final Map<Identifier, Identifier> overrides = new HashMap<>();

    /** Per-block predicated overrides, in registration order; first all-match wins. */
    private final Map<Identifier, List<PredicatedEntry>> predicatedOverrides = new HashMap<>();

    /** Tag bindings (plain and predicated) in registration order. */
    private final List<TagEntry> tagEntries = new ArrayList<>();

    private record PredicatedEntry(List<BlockStatePredicate.Requirement> requirements, Identifier materialId) {}
    private record TagEntry(Identifier tagId, List<BlockStatePredicate.Requirement> requirements, Identifier materialId) {}

    // ------------------------------------------------------------------ mutation

    /** Registers an exact, predicate-free per-block binding. */
    public void addOverride(Identifier blockId, Identifier materialId) {
        overrides.put(blockId, materialId);
    }

    /** Registers a predicated per-block binding (checked before the plain override). */
    public void addOverride(Identifier blockId, List<BlockStatePredicate.Requirement> requirements,
                            Identifier materialId) {
        if (requirements.isEmpty()) {
            overrides.put(blockId, materialId);
            return;
        }
        predicatedOverrides.computeIfAbsent(blockId, k -> new ArrayList<>())
                .add(new PredicatedEntry(List.copyOf(requirements), materialId));
    }

    /** Registers a predicate-free tag binding (first-registered matching tag wins). */
    public void addTagBinding(Identifier tagId, Identifier materialId) {
        tagEntries.add(new TagEntry(tagId, List.of(), materialId));
    }

    /** Registers a predicated tag binding (tag must contain the block AND the predicate hold). */
    public void addTagBinding(Identifier tagId, List<BlockStatePredicate.Requirement> requirements,
                              Identifier materialId) {
        tagEntries.add(new TagEntry(tagId, List.copyOf(requirements), materialId));
    }

    public void clear() {
        overrides.clear();
        predicatedOverrides.clear();
        tagEntries.clear();
    }

    // ------------------------------------------------------------------ resolution

    /** Predicate-free convenience: resolves with no blockstate properties available. */
    public Identifier materialFor(Identifier blockId, TagMembership tags) {
        return materialFor(blockId, PropertyView.EMPTY, tags);
    }

    /**
     * Resolves the material id for {@code blockId}. Order: predicated overrides (first
     * all-match) → plain override → tag entries in order (predicate must also hold) → fallback.
     */
    public Identifier materialFor(Identifier blockId, PropertyView props, TagMembership tags) {
        // 1. predicated overrides
        List<PredicatedEntry> pred = predicatedOverrides.get(blockId);
        if (pred != null) {
            for (PredicatedEntry e : pred) {
                if (BlockStatePredicate.matchesAll(e.requirements(), props)) {
                    return e.materialId();
                }
            }
        }
        // 2. plain override
        Identifier override = overrides.get(blockId);
        if (override != null) {
            return override;
        }
        // 3. tag entries in registration order
        for (TagEntry e : tagEntries) {
            if (tags.contains(e.tagId(), blockId) && BlockStatePredicate.matchesAll(e.requirements(), props)) {
                return e.materialId();
            }
        }
        // 4. fallback
        return MaterialRegistry.FALLBACK_ID;
    }
}
