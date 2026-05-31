package net.rainbowcreation.orge.phase;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the shipped default ORGE datapack: the steam + ice materials parse and carry the
 * §7 phase fields that close the water↔steam and water↔ice cycles. Reads the real resource
 * files from the classpath, so a typo in the JSON fails here.
 */
class DefaultPhaseDataTest {

    private static Identifier orge(String path) { return Identifier.fromNamespaceAndPath("orge", path); }
    private static Identifier mc(String path) { return Identifier.fromNamespaceAndPath("minecraft", path); }

    private static JsonElement resource(String path) {
        try (InputStream in = DefaultPhaseDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ActiveMaterials.State loadDefaultPack() {
        Map<Identifier, JsonElement> materials = new LinkedHashMap<>();
        for (String id : List.of("air", "water", "lava", "generic_solid", "steam", "ice")) {
            materials.put(orge(id), resource("/data/orge/orge/materials/" + id + ".json"));
        }
        List<JsonElement> bindings = List.of(resource("/data/orge/orge/bindings/default.json"));
        return ActiveMaterials.buildState(materials, bindings);
    }

    @Test
    void steamMaterialCondensesBackToWater() {
        MaterialRegistry reg = loadDefaultPack().registry();
        Material steam = reg.get(orge("steam")).orElseThrow();
        assertEquals(373.15f, steam.minTemp(), 0.01f);
        assertEquals(mc("water"), steam.minTarget(), "steam condenses back to water below 373.15 K");
    }

    @Test
    void iceMaterialMeltsBackToWater() {
        MaterialRegistry reg = loadDefaultPack().registry();
        Material ice = reg.get(orge("ice")).orElseThrow();
        assertEquals(273.15f, ice.maxTemp(), 0.01f);
        assertEquals(mc("water"), ice.maxTarget(), "ice melts to water above 273.15 K");
    }

    @Test
    void waterBoilsToSteamAndFreezesToIce() {
        MaterialRegistry reg = loadDefaultPack().registry();
        Material water = reg.get(orge("water")).orElseThrow();
        assertEquals(orge("steam"), water.maxTarget(), "water boils to the orge:steam block");
        assertEquals(mc("ice"), water.minTarget(), "water freezes to minecraft:ice");
    }
}
