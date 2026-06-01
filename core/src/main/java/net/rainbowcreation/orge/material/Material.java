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
 * @param minTemp             K — lower threshold; below it the cell becomes {@code minTarget}
 * @param maxTemp             K — upper threshold; above it the cell becomes {@code maxTarget}
 * @param minTarget           MATERIAL id placed when temperature drops below {@code minTemp}, or null
 * @param maxTarget           MATERIAL id placed when temperature rises above {@code maxTemp}, or null
 * @param representativeBlock block placed when something <i>becomes</i> this material; defaults to {@code minecraft:air}
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
        float minTemp,
        float maxTemp,
        Identifier minTarget,
        Identifier maxTarget,
        Identifier representativeBlock,
        boolean pinned
) {
    /**
     * Compat ctor mirroring the legacy 11-arg solid signature
     * {@code (id, cond, heatCap, viscosity, defaultMass, molarMass, maxTemp, minTemp, maxTarget, minTarget, repr)}.
     * The legacy {@code viscosity} arg is intentionally ignored for movability: a legacy 11-arg material
     * was {@code State.SOLID} → frozen, so the new record gets {@code viscosity = +∞}. To match the old
     * positional semantics exactly, {@code minMass} defaults to {@code 0} (the legacy {@code min_flow_mass}
     * default) and {@code maxMass} to {@code defaultMass} (the legacy {@code maxMass()} fallback); repr
     * falls back to {@code minecraft:air} when null. Kept only so the existing positional call sites
     * compile; new code should use {@link #builder(Identifier)} (whose min_mass default is defaultMass).
     */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float legacyViscosity, float defaultMass, float molarMass,
                    float maxTemp, float minTemp,
                    Identifier maxTarget, Identifier minTarget, Identifier representativeBlock) {
        this(id, thermalConductivity, heatCapacity, molarMass, defaultMass,
                Float.NaN, Float.POSITIVE_INFINITY, 0f, defaultMass,
                minTemp, maxTemp, minTarget, maxTarget,
                representativeBlock != null ? representativeBlock
                        : Identifier.fromNamespaceAndPath("minecraft", "air"),
                false);
    }

    /**
     * Compat ctor mirroring the legacy 13-arg solid+pin signature
     * {@code (… repr, defaultTemperature, pinned)}. Frozen ({@code viscosity = +∞}); see the 11-arg ctor.
     */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float legacyViscosity, float defaultMass, float molarMass,
                    float maxTemp, float minTemp,
                    Identifier maxTarget, Identifier minTarget, Identifier representativeBlock,
                    float defaultTemperature, boolean pinned) {
        this(id, thermalConductivity, heatCapacity, molarMass, defaultMass,
                defaultTemperature, Float.POSITIVE_INFINITY, 0f, defaultMass,
                minTemp, maxTemp, minTarget, maxTarget,
                representativeBlock != null ? representativeBlock
                        : Identifier.fromNamespaceAndPath("minecraft", "air"),
                pinned);
    }

    /**
     * Compat ctor mirroring the legacy {@code (… defaultTemperature, pinned, fluid)} signature.
     * {@code fluid} now folds into the movability invariant: {@code fluid=true} → a finite viscosity
     * (the passed {@code legacyViscosity}, defaulting to 0) so {@link #movable()} is true; otherwise
     * {@code +∞} (frozen). Kept for positional test call sites; new code uses {@link #builder(Identifier)}.
     */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float legacyViscosity, float defaultMass, float molarMass,
                    float maxTemp, float minTemp,
                    Identifier maxTarget, Identifier minTarget, Identifier representativeBlock,
                    float defaultTemperature, boolean pinned, boolean fluid) {
        this(id, thermalConductivity, heatCapacity, molarMass, defaultMass,
                defaultTemperature,
                fluid ? legacyViscosity : Float.POSITIVE_INFINITY,
                0f, defaultMass, minTemp, maxTemp, minTarget, maxTarget,
                representativeBlock != null ? representativeBlock
                        : Identifier.fromNamespaceAndPath("minecraft", "air"),
                pinned);
    }

    /**
     * Compat ctor mirroring the legacy three-mass {@code (… pinned, fluid, minFlowMass, maxMass, gas)}
     * signature. Movability = {@code fluid || gas}; {@code minFlowMass} → {@code minMass}; {@code maxMass}
     * (0 ⇒ {@code defaultMass}). Kept for positional test call sites; new code uses {@link #builder}.
     */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float legacyViscosity, float defaultMass, float molarMass,
                    float maxTemp, float minTemp,
                    Identifier maxTarget, Identifier minTarget, Identifier representativeBlock,
                    float defaultTemperature, boolean pinned,
                    boolean fluid, float minFlowMass, float maxMass, boolean gas) {
        this(id, thermalConductivity, heatCapacity, molarMass, defaultMass,
                defaultTemperature,
                (fluid || gas) ? legacyViscosity : Float.POSITIVE_INFINITY,
                minFlowMass, maxMass > 0f ? maxMass : defaultMass,
                minTemp, maxTemp, minTarget, maxTarget,
                representativeBlock != null ? representativeBlock
                        : Identifier.fromNamespaceAndPath("minecraft", "air"),
                pinned);
    }

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
     * min/maxMass → {@code defaultMass}, representativeBlock → {@code minecraft:air}, pinned → false.
     * Optional temps default to ∓∞ and targets to null.
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
                    : Identifier.fromNamespaceAndPath("minecraft", "air");         // absent => minecraft:air
            return new Material(id, thermalConductivity, heatCapacity, molarMass, defaultMass,
                    defaultTemperature, visc, minM, maxM, minTemp, maxTemp,
                    minTarget, maxTarget, repr, pinned);
        }
    }
}
