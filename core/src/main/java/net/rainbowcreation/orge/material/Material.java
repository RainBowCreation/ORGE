package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;

/**
 * Flat, constant per-material properties (DESIGN.md §6). No temperature-dependent
 * curves in v1 — those are a future "realistic curves" addon seeded from {@code /old}.
 *
 * @param id                   this material's namespaced id (e.g. {@code orge:stone})
 * @param thermalConductivity  W/(m·K)
 * @param heatCapacity         J/(kg·K)
 * @param viscosity            Pa·s — reserved for Phase-2 fluid flow
 * @param defaultMass          kg per 1 m³ cell
 * @param molarMass            kg/mol
 * @param boilingPoint         K
 * @param freezingPoint        K
 * @param boilingTarget        block id placed when this boils (temperature above {@code boilingPoint}), or null
 * @param freezingTarget       block id placed when this freezes (temperature below {@code freezingPoint}), or null
 * @param representativeBlock  block placed when something <i>becomes</i> this material
 */
public record Material(
        Identifier id,
        float thermalConductivity,
        float heatCapacity,
        float viscosity,
        float defaultMass,
        float molarMass,
        float boilingPoint,
        float freezingPoint,
        Identifier boilingTarget,
        Identifier freezingTarget,
        Identifier representativeBlock
) {
    // TODO(phase: materials): builder + validate non-negative constants.
}
