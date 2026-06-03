package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.Orge;

/**
 * The block → material FIRST-TOUCH rule (spec Part 1). Maps a vanilla (or modded) block id to
 * {@code orge:<path>} if such a material is registered, else the global fallback
 * {@code orge:generic_solid}. Used ONLY for a cell with no stored material (freshly generated /
 * never simulated) or an explicit player PLACE — never to re-derive identity for a stored cell.
 */
public final class BlockMaterialRule {
    private BlockMaterialRule() {}

    public static Identifier firstTouch(Identifier blockId, MaterialRegistry registry) {
        Identifier candidate = Identifier.fromNamespaceAndPath(Orge.MOD_ID, blockId.getPath());
        return registry.get(candidate).isPresent() ? candidate : MaterialRegistry.FALLBACK_ID;
    }

    /** Convenience: resolve straight to the {@link Material} (fallback guaranteed registered). */
    public static Material firstTouchMaterial(Identifier blockId, MaterialRegistry registry) {
        return registry.getOrFallback(firstTouch(blockId, registry));
    }
}
