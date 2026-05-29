package net.rainbowcreation.orge.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * Pure static helpers for parsing datapack JSON into a {@link MaterialRegistry}
 * and {@link MaterialBindings}.  No Minecraft {@code ResourceManager} dependency —
 * callers (the reload listener, tests) supply the pre-fetched JSON elements.
 *
 * <h2>Material files</h2>
 * <p>Each entry in {@code files} maps a material id (e.g. {@code orge:water}) to its
 * JSON body.  Body fields follow the snake_case convention documented in
 * {@link MaterialCodec}.</p>
 *
 * <h2>Bindings files</h2>
 * <pre>
 * {
 *   "tags":      [ { "tag": "c:stones", "material": "orge:generic_solid" }, … ],
 *   "overrides": { "minecraft:iron_block": "orge:iron", … }
 * }
 * </pre>
 * <p>Both {@code tags} and {@code overrides} keys are optional; their absence is
 * treated as an empty collection.  Tag bindings are registered in array order so
 * the first entry has the highest priority.</p>
 */
public final class MaterialData {

    private MaterialData() {}

    // -------------------------------------------------------------------------
    // Material loading
    // -------------------------------------------------------------------------

    /**
     * Decode each entry in {@code files} and register it into {@code into}.
     *
     * @param files map of material id → JSON body element (snake_case keys)
     * @param into  the registry to populate; existing entries for the same id are replaced
     * @throws IllegalArgumentException if any material entry fails to decode (message includes the
     *                                   failing material id)
     */
    public static void loadMaterials(Map<Identifier, JsonElement> files, MaterialRegistry into) {
        for (Map.Entry<Identifier, JsonElement> entry : files.entrySet()) {
            Identifier id = entry.getKey();
            try {
                Material material = MaterialCodec.fromJson(id, entry.getValue());
                into.put(material);
            } catch (Exception e) {
                throw new IllegalArgumentException("failed to load material " + id + ": " + e.getMessage(), e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bindings loading
    // -------------------------------------------------------------------------

    /**
     * Parse each element in {@code files} as a bindings object and populate {@code into}.
     *
     * <p>Each file is processed in list order; within a file, {@code tags} array
     * entries are processed in array order (index 0 = highest priority).  Entries
     * from later files can override tag/override registrations from earlier files
     * (last writer wins for overrides; for tag bindings, if the same tag id is bound
     * more than once across files in one load, the latest material value wins but the
     * tag keeps its first-seen priority position).</p>
     *
     * @param files list of JSON elements, each a bindings object
     * @param into  the bindings instance to populate
     * @throws IllegalArgumentException if any tag-binding entry is missing the {@code "tag"} or
     *                                   {@code "material"} key, if any id string is malformed
     *                                   (contains illegal characters), or if a JSON value is
     *                                   the wrong type (e.g. {@code "tags"} is not an array)
     */
    public static void loadBindings(List<JsonElement> files, MaterialBindings into) {
        for (JsonElement fileElement : files) {
            JsonObject root = fileElement.getAsJsonObject();

            // --- tags (ordered array, optional) ---
            if (root.has("tags")) {
                JsonArray tags = root.getAsJsonArray("tags");
                for (JsonElement tagEntry : tags) {
                    JsonObject obj = tagEntry.getAsJsonObject();
                    if (!obj.has("tag")) {
                        throw new IllegalArgumentException(
                                "material binding entry missing 'tag' key: " + obj);
                    }
                    if (!obj.has("material")) {
                        throw new IllegalArgumentException(
                                "material binding entry missing 'material' key: " + obj);
                    }
                    String tagStr      = obj.get("tag").getAsString();
                    String materialStr = obj.get("material").getAsString();
                    try {
                        Identifier tagId      = Identifier.parse(tagStr);
                        Identifier materialId = Identifier.parse(materialStr);
                        into.addTagBinding(tagId, materialId);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "invalid material binding: tag=\"" + tagStr
                                + "\" material=\"" + materialStr + "\"", e);
                    }
                }
            }

            // --- overrides (object, optional) ---
            if (root.has("overrides")) {
                JsonObject overrides = root.getAsJsonObject("overrides");
                for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
                    String blockStr    = entry.getKey();
                    String materialStr = entry.getValue().getAsString();
                    try {
                        Identifier blockId    = Identifier.parse(blockStr);
                        Identifier materialId = Identifier.parse(materialStr);
                        into.addOverride(blockId, materialId);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "invalid material binding: block=\"" + blockStr
                                + "\" material=\"" + materialStr + "\"", e);
                    }
                }
            }
        }
    }
}
