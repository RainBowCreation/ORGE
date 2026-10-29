package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.Identifier;

/**
 * The visual effects a material declares in its JSON {@code "effects"} object. Render only — the
 * engine never reads them. Every effect is a vanilla particle (and optional sound) the server sends,
 * so a vanilla client sees it with no client mod.
 *
 * <pre>{@code
 * "effects": {
 *   "suspended": { "particle": "minecraft:falling_water", "under_solid": "minecraft:dripping_water" },
 *   "boiling":   { "particle": "minecraft:bubble_column_up",
 *                  "sound": "minecraft:block.bubble_column.upwards_ambient", "sound_chance": 0.05 }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><b>suspended</b> — the material is in a cell drawn as air (a gas, or a liquid trace: less than half
 *       of the thinnest vanilla level, {@code default_mass / 16}). Shown with chance
 *       {@code min(1, mass / (default_mass / 16))} per step; {@code under_solid}
 *       replaces the particle when a solid block is right above (a drip hanging from a ceiling).</li>
 *   <li><b>boiling</b> — the cell is at or above the material's {@code max_temp} (on its boiling
 *       plateau) and is drawn as a block. Shown inside the cell.</li>
 * </ul>
 * A {@code particle} is a particle id string or a full particle object as vanilla writes it
 * ({@code {"type": "minecraft:dust", "color": ..., "scale": ...}}). {@code min_kg} (default 0) is the mass
 * the material needs in the cell before the effect shows.
 */
public record MaterialEffects(Effect suspended, Effect boiling) {

    public static final MaterialEffects NONE = new MaterialEffects(null, null);

    /**
     * One effect: the particle (as particle-options JSON), an optional particle for a cell under a solid
     * block, an optional sound with its per-emission chance, and the mass threshold.
     */
    public record Effect(JsonElement particle, JsonElement underSolid, Identifier sound, float soundChance,
                         float minKg) {}

    /** Parses the {@code "effects"} member of a material body; absent ⇒ {@link #NONE}. */
    static MaterialEffects parse(Identifier id, JsonElement effects) {
        if (effects == null || effects.isJsonNull()) {
            return NONE;
        }
        if (!effects.isJsonObject()) {
            throw new IllegalArgumentException("material " + id + ": effects must be an object");
        }
        JsonObject o = effects.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!key.equals("suspended") && !key.equals("boiling")) {
                throw new IllegalArgumentException("material " + id + ": unknown effect '" + key
                        + "' (known: suspended, boiling)");
            }
        }
        return new MaterialEffects(effect(id, "suspended", o.get("suspended")), effect(id, "boiling", o.get("boiling")));
    }

    private static Effect effect(Identifier id, String name, JsonElement e) {
        if (e == null) {
            return null;
        }
        if (!e.isJsonObject() || !e.getAsJsonObject().has("particle")) {
            throw new IllegalArgumentException("material " + id + ": effect " + name + " needs a particle");
        }
        JsonObject o = e.getAsJsonObject();
        Identifier sound = o.has("sound") ? Identifier.parse(o.get("sound").getAsString()) : null;
        float chance = o.has("sound_chance") ? o.get("sound_chance").getAsFloat() : 0.1f;
        float minKg = o.has("min_kg") ? o.get("min_kg").getAsFloat() : 0f;
        return new Effect(particle(o.get("particle")), o.has("under_solid") ? particle(o.get("under_solid")) : null,
                sound, chance, minKg);
    }

    /** A bare id string becomes {@code {"type": id}}, the form vanilla's particle codec reads. */
    private static JsonElement particle(JsonElement p) {
        if (p.isJsonPrimitive()) {
            JsonObject o = new JsonObject();
            o.add("type", new JsonPrimitive(p.getAsString()));
            return o;
        }
        return p;
    }
}
