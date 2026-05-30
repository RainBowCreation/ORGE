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
 * @param defaultTemperature   seed/natural temperature (K) for this material; {@link Float#NaN} when absent
 * @param pinned               when true the cell temperature is held at {@code defaultTemperature} every tick
 * @param fluid                when true this material participates in Phase-2 mass-conservative flow
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
        Identifier representativeBlock,
        float defaultTemperature,
        boolean pinned,
        boolean fluid
) {
    /** Backward-compatible constructor: no natural/pin temperature, not a source, not a fluid. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float boilingPoint, float freezingPoint,
                    Identifier boilingTarget, Identifier freezingTarget,
                    Identifier representativeBlock) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock,
                Float.NaN, false, false);
    }

    /** Constructor with pin temperature + pinned flag, not a fluid. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float boilingPoint, float freezingPoint,
                    Identifier boilingTarget, Identifier freezingTarget,
                    Identifier representativeBlock, float defaultTemperature, boolean pinned) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock,
                defaultTemperature, pinned, false);
    }

    /** True when a natural/seed/pin temperature is defined (i.e. {@code default_temperature} present). */
    public boolean hasDefaultTemperature() {
        return !Float.isNaN(defaultTemperature);
    }
    // TODO(phase: materials): builder + validate non-negative constants.
}
