package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Serialization/deserialization for {@link Material} using Mojang's DFU codec
 * ({@code com.mojang.serialization}).
 *
 * <h2>Design: {@code fromJson} helper rather than a bare {@code Codec<Material>}</h2>
 * <p>A Material's {@code id} comes from the file path (supplied by the loader),
 * not from the JSON body.  Exposing a plain {@code Codec<Material>} would force callers
 * to somehow inject the id, which is awkward with DFU's immutable codec pipeline.
 * Instead we expose:</p>
 * <ol>
 *   <li>{@link #fromJson(Identifier, JsonElement)} — the sole public entry point;
 *       decodes the body and attaches the caller-supplied id, throwing a clean
 *       {@link IllegalArgumentException} (naming the missing field + the material id)
 *       on failure.</li>
 * </ol>
 *
 * <h2>Strict required-or-error (canonical schema)</h2>
 * <p>The five REQUIRED fields error if absent; clean data is mandatory:</p>
 * <ul>
 *   <li><b>Required:</b> {@code thermal_conductivity}, {@code heat_capacity}, {@code molar_mass},
 *       {@code default_mass}, {@code default_temperature}</li>
 *   <li><b>Optional with defaults:</b>
 *     {@code viscosity} → absent ⇒ {@code +∞} frozen ({@code 0} stays {@code 0}, fastest),
 *     {@code min_mass} → {@code default_mass}, {@code max_mass} → {@code default_mass},
 *     {@code max_temp} → +∞, {@code min_temp} → -∞, {@code pinned} → false,
 *     {@code representative_block} → {@code minecraft:<path>} (derived from the id's path; air-downgrade
 *     for nonexistent blocks happens at the registry boundary)</li>
 *   <li><b>Optional MATERIAL-id targets:</b> {@code min_target}, {@code max_target}
 *       (paired: if {@code min_temp} is present {@code min_target} is required;
 *       if {@code max_temp} is present {@code max_target} is required)</li>
 * </ul>
 */
public final class MaterialCodec {

    private MaterialCodec() {}

    // -------------------------------------------------------------------------
    // Internal record: the canonical body fields (id is supplied separately by loader)
    // -------------------------------------------------------------------------

    record BodyData(
            float thermalConductivity,
            float heatCapacity,
            float molarMass,
            float defaultMass,
            float defaultTemperature,
            Optional<Float> viscosity,
            Optional<Float> minMass,
            Optional<Float> maxMass,
            Optional<Float> yieldStress,
            float emissivity,
            float thermalExpansion,
            float latentHeatMin,
            float latentHeatMax,
            float tRefGas,
            float maxTemp,
            float minTemp,
            Optional<Identifier> maxTarget,
            Optional<Identifier> minTarget,
            Optional<Identifier> representativeBlock,
            boolean pinned
    ) {
        // Accessors for the two-half split (DFU RecordCodecBuilder.group caps at 16 args; this
        // body has 20 fields, so the codec is composed from CoreHalf + ThermalHalf — see below).
        CoreHalf core() {
            return new CoreHalf(thermalConductivity, heatCapacity, molarMass, defaultMass,
                    defaultTemperature, viscosity, minMass, maxMass, yieldStress,
                    maxTemp, minTemp, maxTarget, minTarget, representativeBlock, pinned);
        }
        ThermalHalf thermal() {
            return new ThermalHalf(emissivity, thermalExpansion, latentHeatMin, latentHeatMax, tRefGas);
        }
        static BodyData join(CoreHalf c, ThermalHalf t) {
            return new BodyData(c.thermalConductivity(), c.heatCapacity(), c.molarMass(),
                    c.defaultMass(), c.defaultTemperature(), c.viscosity(), c.minMass(), c.maxMass(),
                    c.yieldStress(), t.emissivity(), t.thermalExpansion(), t.latentHeatMin(),
                    t.latentHeatMax(), t.tRefGas(), c.maxTemp(), c.minTemp(), c.maxTarget(),
                    c.minTarget(), c.representativeBlock(), c.pinned());
        }
    }

    /** First half: the original 15 canonical body fields (within DFU's 16-arg group limit). */
    record CoreHalf(
            float thermalConductivity,
            float heatCapacity,
            float molarMass,
            float defaultMass,
            float defaultTemperature,
            Optional<Float> viscosity,
            Optional<Float> minMass,
            Optional<Float> maxMass,
            Optional<Float> yieldStress,
            float maxTemp,
            float minTemp,
            Optional<Identifier> maxTarget,
            Optional<Identifier> minTarget,
            Optional<Identifier> representativeBlock,
            boolean pinned
    ) {}

    /** Second half: the v4 §1.2 / law #8 thermal columns (ε / β / latent / per-gas T_ref). */
    record ThermalHalf(
            float emissivity,
            float thermalExpansion,
            float latentHeatMin,
            float latentHeatMax,
            float tRefGas
    ) {}

    // -------------------------------------------------------------------------
    // Internal codecs for the canonical body (five required + the optionals).
    // Split into two halves because DFU's RecordCodecBuilder.group caps at 16 args.
    // -------------------------------------------------------------------------

    private static final Codec<CoreHalf> CORE_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    // Required — fieldOf (not optionalFieldOf): absent ⇒ codec error.
                    Codec.FLOAT.fieldOf("thermal_conductivity")
                            .forGetter(CoreHalf::thermalConductivity),
                    Codec.FLOAT.fieldOf("heat_capacity")
                            .forGetter(CoreHalf::heatCapacity),
                    Codec.FLOAT.fieldOf("molar_mass")
                            .forGetter(CoreHalf::molarMass),
                    Codec.FLOAT.fieldOf("default_mass")
                            .forGetter(CoreHalf::defaultMass),
                    Codec.FLOAT.fieldOf("default_temperature")
                            .forGetter(CoreHalf::defaultTemperature),
                    // Optional — absence handled by Material.Builder's canonical defaults.
                    Codec.FLOAT.optionalFieldOf("viscosity")
                            .forGetter(CoreHalf::viscosity),
                    Codec.FLOAT.optionalFieldOf("min_mass")
                            .forGetter(CoreHalf::minMass),
                    Codec.FLOAT.optionalFieldOf("max_mass")
                            .forGetter(CoreHalf::maxMass),
                    // law §8 threshold axis — absent ⇒ 0 (pure fluid), applied by the builder default.
                    Codec.FLOAT.optionalFieldOf("yield_stress")
                            .forGetter(CoreHalf::yieldStress),
                    Codec.FLOAT.optionalFieldOf("max_temp", Float.POSITIVE_INFINITY)
                            .forGetter(CoreHalf::maxTemp),
                    Codec.FLOAT.optionalFieldOf("min_temp", Float.NEGATIVE_INFINITY)
                            .forGetter(CoreHalf::minTemp),
                    Identifier.CODEC.optionalFieldOf("max_target")
                            .forGetter(CoreHalf::maxTarget),
                    Identifier.CODEC.optionalFieldOf("min_target")
                            .forGetter(CoreHalf::minTarget),
                    Identifier.CODEC.optionalFieldOf("representative_block")
                            .forGetter(CoreHalf::representativeBlock),
                    Codec.BOOL.optionalFieldOf("pinned", false)
                            .forGetter(CoreHalf::pinned)
            ).apply(instance, CoreHalf::new)
    );

    private static final Codec<ThermalHalf> THERMAL_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    // v4 §1.2 / law #8 — radiation, convection, latent heat, per-gas EOS ref temp.
                    // All optional, absent ⇒ 0.0 (non-declaring materials reproduce prior behaviour).
                    Codec.FLOAT.optionalFieldOf("emissivity", 0.0f)
                            .forGetter(ThermalHalf::emissivity),
                    Codec.FLOAT.optionalFieldOf("thermal_expansion", 0.0f)
                            .forGetter(ThermalHalf::thermalExpansion),
                    Codec.FLOAT.optionalFieldOf("latent_heat_min", 0.0f)
                            .forGetter(ThermalHalf::latentHeatMin),
                    Codec.FLOAT.optionalFieldOf("latent_heat_max", 0.0f)
                            .forGetter(ThermalHalf::latentHeatMax),
                    Codec.FLOAT.optionalFieldOf("t_ref_gas", 0.0f)
                            .forGetter(ThermalHalf::tRefGas)
            ).apply(instance, ThermalHalf::new)
    );

    // The two halves read from the SAME flat JSON object (both are map codecs over the body).
    private static final Codec<BodyData> BODY_CODEC =
            Codec.pair(CORE_CODEC, THERMAL_CODEC).xmap(
                    pair -> BodyData.join(pair.getFirst(), pair.getSecond()),
                    bd -> com.mojang.datafixers.util.Pair.of(bd.core(), bd.thermal()));

    // -------------------------------------------------------------------------
    // Primary public entry point
    // -------------------------------------------------------------------------

    /**
     * Decode a {@link Material} from a JSON body element plus a caller-supplied id.
     *
     * <p>The {@code id} argument is typically derived from the datapack file path
     * (e.g. {@code data/orge/orge/materials/stone.json} → {@code orge:stone}).
     *
     * @param id   the namespaced id to attach to the decoded material
     * @param body a {@code JsonElement} containing the body fields
     * @return the decoded {@link Material}
     * @throws IllegalArgumentException if the JSON is missing a required field
     *                                   (the message names the field + material id),
     *                                   violates the temp⇒target pairing rule, or
     *                                   contains a malformed value
     */
    public static Material fromJson(Identifier id, JsonElement body) {
        DataResult<BodyData> result = BODY_CODEC.parse(JsonOps.INSTANCE, body);
        BodyData bd = result.getOrThrow(err ->
                new IllegalArgumentException("material " + id + ": " + err));

        // Pairing rule: a phase threshold without its target is invalid data.
        if (Float.isFinite(bd.minTemp()) && bd.minTarget().isEmpty()) {
            throw new IllegalArgumentException(
                    "material " + id + ": min_temp present requires min_target");
        }
        if (Float.isFinite(bd.maxTemp()) && bd.maxTarget().isEmpty()) {
            throw new IllegalArgumentException(
                    "material " + id + ": max_temp present requires max_target");
        }
        if (bd.pinned() && Float.isNaN(bd.defaultTemperature())) {
            throw new IllegalArgumentException(
                    "material " + id + ": pinned=true requires default_temperature");
        }

        // The builder applies the canonical absent-defaults (viscosity → +∞ frozen,
        // min/max_mass → default_mass, representative_block → minecraft:<path>).
        Material.Builder b = Material.builder(id)
                .thermalConductivity(bd.thermalConductivity())
                .heatCapacity(bd.heatCapacity())
                .molarMass(bd.molarMass())
                .defaultMass(bd.defaultMass())
                .defaultTemperature(bd.defaultTemperature())
                .minTemp(bd.minTemp())
                .maxTemp(bd.maxTemp())
                .emissivity(bd.emissivity())
                .thermalExpansion(bd.thermalExpansion())
                .latentHeatMin(bd.latentHeatMin())
                .latentHeatMax(bd.latentHeatMax())
                .tRefGas(bd.tRefGas())
                .pinned(bd.pinned());
        bd.viscosity().ifPresent(b::viscosity);
        bd.minMass().ifPresent(b::minMass);
        bd.maxMass().ifPresent(b::maxMass);
        bd.yieldStress().ifPresent(b::yieldStress);
        bd.minTarget().ifPresent(b::minTarget);
        bd.maxTarget().ifPresent(b::maxTarget);
        bd.representativeBlock().ifPresent(b::representativeBlock);
        return b.build();
    }
}
