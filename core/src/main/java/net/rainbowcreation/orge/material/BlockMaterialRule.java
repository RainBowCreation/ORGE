package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.Orge;

import java.util.Optional;

/**
 * The block → material FIRST-TOUCH rule (spec Part 1). Maps a vanilla (or modded) block id to
 * {@code orge:<path>} if such a material is registered, else the global fallback
 * {@code orge:generic_solid}. Used ONLY for a cell with no stored material (freshly generated /
 * never simulated) or an explicit player PLACE — never to re-derive identity for a stored cell.
 */
public final class BlockMaterialRule {
    /** The piston's in-flight carrier block: it stands in a cell only while a pushed or pulled block
     *  (or the piston head) is moving, then is replaced by the block it carries. */
    public static final Identifier MOVING_PISTON = Identifier.fromNamespaceAndPath("minecraft", "moving_piston");

    private BlockMaterialRule() {}

    /**
     * True for a block that holds no substance of its own — a transient carrier the world swaps back to
     * a real block within a few ticks ({@code minecraft:moving_piston}). Its first-touch is the fallback
     * {@code orge:generic_solid}, so a placement captured off it would inject a fabricated solid; the
     * capture skips it and records the block it settles into instead.
     */
    public static boolean isTransient(Identifier blockId) {
        return MOVING_PISTON.equals(blockId);
    }

    public static Identifier firstTouch(Identifier blockId, MaterialRegistry registry) {
        Optional<Identifier> alias = registry.aliasOf(blockId);
        if (alias.isPresent() && registry.get(alias.get()).isPresent()) {
            return alias.get();
        }
        Identifier candidate = Identifier.fromNamespaceAndPath(Orge.MOD_ID, blockId.getPath());
        return registry.get(candidate).isPresent() ? candidate : MaterialRegistry.FALLBACK_ID;
    }

    /** Convenience: resolve straight to the {@link Material} (fallback guaranteed registered). */
    public static Material firstTouchMaterial(Identifier blockId, MaterialRegistry registry) {
        return registry.getOrFallback(firstTouch(blockId, registry));
    }
}
