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
 * @param maxTemp              K — upper threshold; above it the cell becomes {@code maxTarget}
 * @param minTemp              K — lower threshold; below it the cell becomes {@code minTarget}
 * @param maxTarget            block id placed when temperature rises above {@code maxTemp}, or null
 * @param minTarget            block id placed when temperature drops below {@code minTemp}, or null
 * @param representativeBlock  block placed when something <i>becomes</i> this material
 * @param defaultTemperature   seed/natural temperature (K) for this material; {@link Float#NaN} when absent
 * @param pinned               when true the cell temperature is held at {@code defaultTemperature} every tick
 * @param state                physical state informing engine physics (flow/buoyancy); see {@link State}
 * @param minFlowMass          kg, flow floor (cohesion); a cell at/below this no longer donates (0 = no floor)
 * @param maxMass              kg, per-cell capacity cap (&gt;= defaultMass); 0 means "fall back to defaultMass"
 */
public record Material(
        Identifier id,
        float thermalConductivity,
        float heatCapacity,
        float viscosity,
        float defaultMass,
        float molarMass,
        float maxTemp,
        float minTemp,
        Identifier maxTarget,
        Identifier minTarget,
        Identifier representativeBlock,
        float defaultTemperature,
        boolean pinned,
        State state,
        float minFlowMass,
        float maxMass
) {
    /**
     * Physical state of a material; informs engine flow/buoyancy (DESIGN spec §1.2).
     * {@code GAS} is a flowing phase too — see {@link #fluid()}.
     */
    public enum State { SOLID, FLUID, GAS, ENTITY, AIR }

    /**
     * Per-cell capacity cap (kg). Stored 0 means "unset" → falls back to {@link #defaultMass()}
     * so the {@code max_mass == default_mass} invariant holds for every material this slice.
     */
    @Override
    public float maxMass() {
        return maxMass > 0f ? maxMass : defaultMass;
    }

    /** Backward-compatible constructor: no natural/pin temperature, solid state. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float maxTemp, float minTemp,
                    Identifier maxTarget, Identifier minTarget,
                    Identifier representativeBlock) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                maxTemp, minTemp, maxTarget, minTarget, representativeBlock,
                Float.NaN, false, State.SOLID, 0f, 0f);
    }

    /** Constructor with pin temperature + pinned flag, solid state. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float maxTemp, float minTemp,
                    Identifier maxTarget, Identifier minTarget,
                    Identifier representativeBlock, float defaultTemperature, boolean pinned) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                maxTemp, minTemp, maxTarget, minTarget, representativeBlock,
                defaultTemperature, pinned, State.SOLID, 0f, 0f);
    }

    /**
     * Compat constructor mirroring the legacy {@code (… pinned, fluid)} signature.
     * {@code fluid=true} → {@link State#FLUID}, otherwise {@link State#SOLID}.
     */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float maxTemp, float minTemp,
                    Identifier maxTarget, Identifier minTarget,
                    Identifier representativeBlock, float defaultTemperature,
                    boolean pinned, boolean fluid) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                maxTemp, minTemp, maxTarget, minTarget, representativeBlock,
                defaultTemperature, pinned, fluid ? State.FLUID : State.SOLID, 0f, 0f);
    }

    /**
     * Compat constructor mirroring the legacy three-mass {@code (… pinned, fluid, minFlowMass,
     * maxMass, gas)} signature. The {@code (fluid, gas)} pair folds into {@link State}:
     * {@code gas} → {@link State#GAS}, else {@code fluid} → {@link State#FLUID}, else {@link State#SOLID}.
     */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float maxTemp, float minTemp,
                    Identifier maxTarget, Identifier minTarget,
                    Identifier representativeBlock, float defaultTemperature,
                    boolean pinned, boolean fluid, float minFlowMass, float maxMass, boolean gas) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                maxTemp, minTemp, maxTarget, minTarget, representativeBlock,
                defaultTemperature, pinned,
                gas ? State.GAS : (fluid ? State.FLUID : State.SOLID), minFlowMass, maxMass);
    }

    /** True when a natural/seed/pin temperature is defined (i.e. {@code default_temperature} present). */
    public boolean hasDefaultTemperature() {
        return !Float.isNaN(defaultTemperature);
    }

    /**
     * Derived: does this material participate in Phase-2 mass-conservative flow?
     * Both {@code FLUID} and {@code GAS} flow.
     */
    public boolean fluid() {
        return state == State.FLUID || state == State.GAS;
    }

    /** Derived: is this material a gas phase (buoyancy / cross-species rules)? */
    public boolean gas() {
        return state == State.GAS;
    }

    /**
     * Derived: is this material first-class air ({@link State#AIR})? Air is NON-fluid
     * and NON-gas — it is the empty phase fluids may flow into, so the engine can later
     * be told which material is "in-game air".
     */
    public boolean air() {
        return state == State.AIR;
    }
    // TODO(phase: materials): builder + validate non-negative constants.
}
