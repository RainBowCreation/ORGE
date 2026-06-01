package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

import java.util.Optional;
import java.util.function.Function;

/**
 * The pure material → {@code representative_block} render lookup (the second half of the
 * material-id ⇄ block-id indirection). Phase change selects a <b>material</b>
 * ({@link PhaseRule#targetMaterial}); this resolves that material's id to the <b>block</b> actually
 * drawn — a separate lookup, since a material's identity is its id and is never the rendered block.
 * The two may differ (material {@code orge:ice} ⇄ block {@code minecraft:ice}) and multiple
 * materials may share one representative block (e.g. invisible gases all draw {@code minecraft:air}).
 *
 * <p>Pure: the registry is injected as {@code id → Optional<Material>}, so this unit-tests with a
 * fake registry and no live server. The live caller passes
 * {@code ActiveMaterials.current().registry()::get}.</p>
 */
public final class PhaseRenderResolver {

    private PhaseRenderResolver() {}

    /**
     * Resolve {@code materialId} to its {@code representative_block} id, or empty if the material is
     * not registered. Identity stays the material id; the returned id is only what gets drawn.
     */
    public static Optional<Identifier> representativeBlock(
            Function<Identifier, Optional<Material>> registry, Identifier materialId) {
        return registry.apply(materialId).map(Material::representativeBlock);
    }
}
