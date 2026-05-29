package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;

/**
 * Resolves a block to its {@link Material} id (DESIGN.md §6).
 *
 * <p>Resolution order: per-block override → tag binding (e.g. {@code #c:stones →
 * orge:stone}) → global fallback. The blockstate itself therefore encodes which
 * material a cell is; material identity is never stored per cell (DESIGN.md §5).</p>
 */
public final class MaterialBindings {

    // TODO(phase: materials): back these with loaded datapack bindings.
    //  - Map<Identifier /*block id*/, Identifier /*material id*/> overrides
    //  - List<TagBinding(TagKey<Block>, Identifier materialId)> in priority order

    /**
     * The material id bound to the given block id, falling back to
     * {@link MaterialRegistry#FALLBACK_ID} when nothing matches.
     */
    public Identifier materialFor(Identifier blockId) {
        // TODO(phase: materials): consult overrides, then tags, then fallback.
        return MaterialRegistry.FALLBACK_ID;
    }
}
