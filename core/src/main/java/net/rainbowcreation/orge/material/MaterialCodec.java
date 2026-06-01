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
 *       {@link IllegalArgumentException} on failure.</li>
 * </ol>
 *
 * <h2>JSON keys (snake_case)</h2>
 * <ul>
 *   <li><b>Required:</b> {@code thermal_conductivity}, {@code heat_capacity}, {@code default_mass}</li>
 *   <li><b>Optional with defaults:</b>
 *     {@code viscosity} → 0, {@code molar_mass} → 0,
 *     {@code max_temp} → +∞, {@code min_temp} → -∞,
 *     {@code default_temperature} → NaN (absent), {@code pinned} → false,
 *     {@code state} → {@code "solid"} (one of solid/fluid/gas/entity/air),
 *     {@code min_flow_mass} → 0, {@code max_mass} → 0 (= default_mass)</li>
 *   <li><b>Optional nullable ids:</b>
 *     {@code max_target}, {@code min_target}, {@code representative_block} → null</li>
 * </ul>
 */
public final class MaterialCodec {

    private MaterialCodec() {}

    // -------------------------------------------------------------------------
    // Internal record: the body fields (id is supplied separately by loader)
    // -------------------------------------------------------------------------
    // TODO(Task 1.2): strict required-or-error + temp/target pairing rewrite. For now this
    // best-effort adapts the EXISTING (old-schema) JSON into the new canonical record so
    // AllMaterials-style loading keeps working; the JSON files are rewritten in Task 1.3.

    record BodyData(
            float thermalConductivity,
            float heatCapacity,
            Optional<Float> viscosity,
            float defaultMass,
            float molarMass,
            float maxTemp,
            float minTemp,
            Optional<Identifier> maxTarget,
            Optional<Identifier> minTarget,
            Optional<Identifier> representativeBlock,
            float defaultTemperature,
            boolean pinned,
            Optional<Float> minMass,
            Optional<Float> maxMass
    ) {}

    // -------------------------------------------------------------------------
    // Internal codec for the body (16 fields, no id)
    // -------------------------------------------------------------------------

    /**
     * DFU codec for the 16 JSON body fields. The material id is NOT part of
     * this codec — it must be supplied externally via {@link #fromJson}.
     */
    private static final Codec<BodyData> BODY_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.fieldOf("thermal_conductivity")
                            .forGetter(BodyData::thermalConductivity),
                    Codec.FLOAT.fieldOf("heat_capacity")
                            .forGetter(BodyData::heatCapacity),
                    Codec.FLOAT.optionalFieldOf("viscosity")
                            .forGetter(BodyData::viscosity),
                    Codec.FLOAT.fieldOf("default_mass")
                            .forGetter(BodyData::defaultMass),
                    Codec.FLOAT.optionalFieldOf("molar_mass", 0f)
                            .forGetter(BodyData::molarMass),
                    Codec.FLOAT.optionalFieldOf("max_temp", Float.POSITIVE_INFINITY)
                            .forGetter(BodyData::maxTemp),
                    Codec.FLOAT.optionalFieldOf("min_temp", Float.NEGATIVE_INFINITY)
                            .forGetter(BodyData::minTemp),
                    Identifier.CODEC.optionalFieldOf("max_target")
                            .forGetter(BodyData::maxTarget),
                    Identifier.CODEC.optionalFieldOf("min_target")
                            .forGetter(BodyData::minTarget),
                    Identifier.CODEC.optionalFieldOf("representative_block")
                            .forGetter(BodyData::representativeBlock),
                    Codec.FLOAT.optionalFieldOf("default_temperature", Float.NaN)
                            .forGetter(BodyData::defaultTemperature),
                    Codec.BOOL.optionalFieldOf("pinned", false)
                            .forGetter(BodyData::pinned),
                    // min_flow_mass is the old key for the new min_mass floor (best-effort; Task 1.3 renames it).
                    Codec.FLOAT.optionalFieldOf("min_flow_mass")
                            .forGetter(BodyData::minMass),
                    Codec.FLOAT.optionalFieldOf("max_mass")
                            .forGetter(BodyData::maxMass)
            ).apply(instance, BodyData::new)
    );

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
     * @param body a {@code JsonElement} containing the 12 body fields
     * @return the decoded {@link Material}
     * @throws IllegalArgumentException if the JSON is missing a required field or
     *                                   contains a malformed value
     */
    public static Material fromJson(Identifier id, JsonElement body) {
        DataResult<BodyData> result = BODY_CODEC.parse(JsonOps.INSTANCE, body);
        BodyData bd = result.getOrThrow(IllegalArgumentException::new);
        if (bd.pinned() && Float.isNaN(bd.defaultTemperature())) {
            throw new IllegalArgumentException(
                    "material " + id + ": pinned=true requires default_temperature");
        }
        Identifier maxTarget = bd.maxTarget().orElse(null);
        if (maxTarget == null && Float.isFinite(bd.maxTemp())) {
            maxTarget = Identifier.fromNamespaceAndPath("minecraft", "air");
        }
        // Builder applies the canonical absent-defaults (viscosity→+∞, min/maxMass→defaultMass,
        // repr→minecraft:air). default_temperature defaults to NaN here so old JSON without it still
        // loads; Task 1.2 makes that a hard required field.
        Material.Builder b = Material.builder(id)
                .thermalConductivity(bd.thermalConductivity())
                .heatCapacity(bd.heatCapacity())
                .molarMass(bd.molarMass())
                .defaultMass(bd.defaultMass())
                .defaultTemperature(bd.defaultTemperature())
                .minTemp(bd.minTemp())
                .maxTemp(bd.maxTemp())
                .pinned(bd.pinned());
        bd.viscosity().ifPresent(b::viscosity);
        bd.minMass().ifPresent(b::minMass);
        bd.maxMass().ifPresent(b::maxMass);
        if (bd.minTarget().isPresent()) b.minTarget(bd.minTarget().get());
        if (maxTarget != null) b.maxTarget(maxTarget);
        bd.representativeBlock().ifPresent(b::representativeBlock);
        return b.build();
    }
}
