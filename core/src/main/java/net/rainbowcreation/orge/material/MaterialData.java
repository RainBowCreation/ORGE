package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Pure static helpers for parsing datapack JSON into a {@link MaterialRegistry}.
 * No Minecraft {@code ResourceManager} dependency — callers (the reload listener,
 * tests) supply the pre-fetched JSON elements.
 *
 * <h2>Material files</h2>
 * <p>Each entry in {@code files} maps a material id (e.g. {@code orge:water}) to its
 * JSON body.  Body fields follow the snake_case convention documented in
 * {@link MaterialCodec}.</p>
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
}
