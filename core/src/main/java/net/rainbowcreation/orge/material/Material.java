package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;

/**
 * Flat, constant per-material properties — the canonical schema (rebuild §6).
 *
 * <p><b>Movability invariant (spec invariant 1):</b> a material is movable iff its
 * {@link #viscosity()} is finite. There is no separate solid/fluid/gas/air distinction —
 * {@link #movable()} is the single test that replaces every old phase branch. An absent
 * viscosity loads as {@link Float#POSITIVE_INFINITY} ("frozen"), so a material with no
 * declared resistance never moves.</p>
 *
 * @param id                  this material's namespaced id (e.g. {@code orge:stone})
 * @param thermalConductivity W/(m·K)
 * @param heatCapacity        J/(kg·K)
 * @param molarMass           kg/mol
 * @param defaultMass         kg per 1 m³ cell (the resting/seed mass)
 * @param defaultTemperature  seed/natural temperature (K); {@link Float#NaN} when absent
 * @param viscosity           physical resistance (Pa·s); {@link Float#POSITIVE_INFINITY} = frozen/immovable
 * @param minMass             kg, per-cell floor (cohesion); defaults to {@code defaultMass}
 * @param maxMass             kg, per-cell capacity cap; defaults to {@code defaultMass}
 * @param yieldStress         N — law §8 threshold axis (granular static yield); {@code 0} for all
 *                            current fluids (present, deferred). Absent ⇒ {@code 0} (pure fluid)
 * @param emissivity          ε — dimensionless [0,1] radiation emissivity (v4 §1.2 / law #8).
 *                            Gases are radiatively transparent ⇒ {@code 0}; non-declaring ⇒ {@code 0}
 * @param thermalExpansion    β — 1/K volumetric thermal-expansion coefficient (v4 §1.2; convection
 *                            ρ_eff). Absent ⇒ {@code 0}
 * @param latentHeatMin       J/kg — latent heat of the {@code minTemp} transition (v4 §1.2). Absent ⇒ {@code 0}
 * @param latentHeatMax       J/kg — latent heat of the {@code maxTemp} transition (v4 §1.2). Absent ⇒ {@code 0}
 * @param tRefGas             K — per-gas EOS reference temperature (v4 §1.2/§2.1; air 288, steam 373).
 *                            Absent ⇒ {@code 0}, the non-gas convention meaning "not set" ⇒ the engine
 *                            falls back to the global T_ref
 * @param minTemp             K — lower threshold; below it the cell becomes {@code minTarget}
 * @param maxTemp             K — upper threshold; above it the cell becomes {@code maxTarget}
 * @param minTarget           MATERIAL id placed when temperature drops below {@code minTemp}, or null
 * @param maxTarget           MATERIAL id placed when temperature rises above {@code maxTemp}, or null
 * @param representativeBlock block placed when something <i>becomes</i> this material; defaults to
 *                            {@code minecraft:<path>} (the id's path under the {@code minecraft}
 *                            namespace, e.g. {@code orge:blue_ice → minecraft:blue_ice}). When that
 *                            block doesn't exist the registry boundary downgrades it to
 *                            {@code minecraft:air} at placement time
 * @param pinned              when true the cell temperature is held at {@code defaultTemperature} every tick
 */
public record Material(
        Identifier id,
        float thermalConductivity,
        float heatCapacity,
        float molarMass,
        float defaultMass,
        float defaultTemperature,
        float viscosity,
        float minMass,
        float maxMass,
        float yieldStress,
        float emissivity,
        float thermalExpansion,
        float latentHeatMin,
        float latentHeatMax,
        float tRefGas,
        float minTemp,
        float maxTemp,
        Identifier minTarget,
        Identifier maxTarget,
        Identifier representativeBlock,
        boolean pinned
) {
    /** True when a natural/seed/pin temperature is defined (i.e. {@code default_temperature} present). */
    public boolean hasDefaultTemperature() {
        return !Float.isNaN(defaultTemperature);
    }

    /**
     * The single movability test (spec invariant 1): a material is movable iff its viscosity
     * is finite. Replaces every old fluid/gas/air/solid branch.
     */
    public boolean movable() {
        return Float.isFinite(viscosity());
    }

    /** Start a fluent builder for the material {@code id}. Required setters must all be called. */
    public static Builder builder(Identifier id) {
        return new Builder(id);
    }

    /**
     * Fluent builder applying the absent-field defaults: viscosity → {@code +∞} (frozen),
     * min/maxMass → {@code defaultMass}, representativeBlock → {@code minecraft:<path>} (derived from
     * the id's path; air-downgrade for nonexistent blocks happens at the registry boundary), pinned →
     * false. Optional temps default to ∓∞ and targets to null.
     */
    public static final class Builder {
        private final Identifier id;
        // required
        private Float thermalConductivity;
        private Float heatCapacity;
        private Float molarMass;
        private Float defaultMass;
        private Float defaultTemperature;
        // optional (null => apply default at build())
        private Float viscosity;
        private Float minMass;
        private Float maxMass;
        private float yieldStress = 0f; // law §8 threshold axis; absent => 0 (pure fluid)
        private float emissivity = 0f;       // v4 §1.2 ε; absent => 0 (gases transparent / non-declaring)
        private float thermalExpansion = 0f; // v4 §1.2 β [1/K]; absent => 0
        private float latentHeatMin = 0f;    // v4 §1.2 J/kg (minTemp transition); absent => 0
        private float latentHeatMax = 0f;    // v4 §1.2 J/kg (maxTemp transition); absent => 0
        private float tRefGas = 0f;          // v4 §1.2/§2.1 K; absent => 0 ("not set" => global T_ref)
        private float minTemp = Float.NEGATIVE_INFINITY;
        private float maxTemp = Float.POSITIVE_INFINITY;
        private Identifier minTarget;
        private Identifier maxTarget;
        private Identifier representativeBlock;
        private boolean pinned = false;

        private Builder(Identifier id) {
            this.id = id;
        }

        public Builder thermalConductivity(float v) { this.thermalConductivity = v; return this; }
        public Builder heatCapacity(float v) { this.heatCapacity = v; return this; }
        public Builder molarMass(float v) { this.molarMass = v; return this; }
        public Builder defaultMass(float v) { this.defaultMass = v; return this; }
        public Builder defaultTemperature(float v) { this.defaultTemperature = v; return this; }
        public Builder viscosity(float v) { this.viscosity = v; return this; }
        public Builder minMass(float v) { this.minMass = v; return this; }
        public Builder maxMass(float v) { this.maxMass = v; return this; }
        public Builder yieldStress(float v) { this.yieldStress = v; return this; }
        public Builder emissivity(float v) { this.emissivity = v; return this; }
        public Builder thermalExpansion(float v) { this.thermalExpansion = v; return this; }
        public Builder latentHeatMin(float v) { this.latentHeatMin = v; return this; }
        public Builder latentHeatMax(float v) { this.latentHeatMax = v; return this; }
        public Builder tRefGas(float v) { this.tRefGas = v; return this; }
        public Builder minTemp(float v) { this.minTemp = v; return this; }
        public Builder maxTemp(float v) { this.maxTemp = v; return this; }
        public Builder minTarget(Identifier v) { this.minTarget = v; return this; }
        public Builder maxTarget(Identifier v) { this.maxTarget = v; return this; }
        public Builder representativeBlock(Identifier v) { this.representativeBlock = v; return this; }
        public Builder pinned(boolean v) { this.pinned = v; return this; }

        public Material build() {
            if (thermalConductivity == null || heatCapacity == null || molarMass == null
                    || defaultMass == null || defaultTemperature == null) {
                throw new IllegalStateException(
                        "material " + id + ": missing a required field "
                                + "(thermal_conductivity, heat_capacity, molar_mass, default_mass, default_temperature)");
            }
            float visc = viscosity != null ? viscosity : Float.POSITIVE_INFINITY; // absent => frozen
            float minM = minMass != null ? minMass : defaultMass;                 // absent => default_mass
            float maxM = maxMass != null ? maxMass : defaultMass;                 // absent => default_mass
            Identifier repr = representativeBlock != null
                    ? representativeBlock
                    : Identifier.fromNamespaceAndPath("minecraft", id.getPath());  // absent => minecraft:<path>
            return new Material(id, thermalConductivity, heatCapacity, molarMass, defaultMass,
                    defaultTemperature, visc, minM, maxM, yieldStress,
                    emissivity, thermalExpansion, latentHeatMin, latentHeatMax, tRefGas,
                    minTemp, maxTemp, minTarget, maxTarget, repr, pinned);
        }
    }
}
