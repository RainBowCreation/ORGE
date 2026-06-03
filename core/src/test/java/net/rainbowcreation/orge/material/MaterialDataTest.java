package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link MaterialData}.
 */
class MaterialDataTest {

    private MaterialRegistry registry;

    private static Identifier id(String full) {
        return Identifier.parse(full);
    }

    @BeforeEach
    void setUp() {
        registry = new MaterialRegistry();
    }

    // -------------------------------------------------------------------------
    // (a) loadMaterials: in-memory 2-entry map
    // -------------------------------------------------------------------------

    @Test
    void loadMaterials_populatesRegistryWithCorrectFields() {
        String waterJson = """
                {
                  "thermal_conductivity": 0.6,
                  "heat_capacity": 4186.0,
                  "molar_mass": 0.018,
                  "default_mass": 1000.0,
                  "default_temperature": 290.0
                }
                """;
        String ironJson = """
                {
                  "thermal_conductivity": 80.0,
                  "heat_capacity": 450.0,
                  "molar_mass": 0.056,
                  "default_mass": 7874.0,
                  "default_temperature": 290.0
                }
                """;

        Identifier waterId = id("orge:water");
        Identifier ironId  = id("orge:iron");

        Map<Identifier, JsonElement> files = new HashMap<>();
        files.put(waterId, JsonParser.parseString(waterJson));
        files.put(ironId,  JsonParser.parseString(ironJson));

        MaterialData.loadMaterials(files, registry);

        // Both materials are present
        assertTrue(registry.get(waterId).isPresent(), "water should be in registry");
        assertTrue(registry.get(ironId).isPresent(),  "iron should be in registry");

        Material water = registry.get(waterId).get();
        assertEquals(waterId, water.id());
        assertEquals(0.6f,    water.thermalConductivity(), 1e-5f);
        assertEquals(4186.0f, water.heatCapacity(),        1e-2f);
        assertEquals(1000.0f, water.defaultMass(),         1e-2f);
        assertEquals(0.018f,  water.molarMass(),           1e-5f);

        Material iron = registry.get(ironId).get();
        assertEquals(ironId,  iron.id());
        assertEquals(80.0f,   iron.thermalConductivity(), 1e-4f);
        assertEquals(450.0f,  iron.heatCapacity(),        1e-3f);
        assertEquals(7874.0f, iron.defaultMass(),         1e-2f);
    }

    // -------------------------------------------------------------------------
    // Fix 2 — wrap per-material decode failure with the material id
    // -------------------------------------------------------------------------

    @Test
    void loadMaterials_badEntry_exceptionMessageContainsMaterialId() {
        String goodJson = """
                {
                  "thermal_conductivity": 80.0,
                  "heat_capacity": 450.0,
                  "molar_mass": 0.056,
                  "default_mass": 7874.0,
                  "default_temperature": 290.0
                }
                """;
        // Missing required field "heat_capacity" → MaterialCodec will throw
        String badJson = """
                {
                  "thermal_conductivity": 1.0,
                  "molar_mass": 0.06,
                  "default_mass": 100.0,
                  "default_temperature": 290.0
                }
                """;

        Map<Identifier, JsonElement> files = new HashMap<>();
        files.put(id("orge:good"), JsonParser.parseString(goodJson));
        files.put(id("orge:bad"),  JsonParser.parseString(badJson));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MaterialData.loadMaterials(files, registry));
        assertTrue(ex.getMessage().contains("orge:bad"),
                "exception message should contain the failing material id 'orge:bad', got: " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // (c) Real-resource test: default JSON files from the classpath
    // -------------------------------------------------------------------------

    @Test
    void realResourceTest_defaultMaterials() throws Exception {
        // Load each material from the actual resource files
        String[] names = {"air", "water", "lava", "generic_solid"};
        Map<Identifier, JsonElement> matFiles = new HashMap<>();

        for (String name : names) {
            String path = "/data/orge/orge/materials/" + name + ".json";
            try (InputStream is = getClass().getResourceAsStream(path)) {
                assertNotNull(is, "Resource must exist: " + path);
                JsonElement body = JsonParser.parseReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8));
                matFiles.put(Identifier.parse("orge:" + name), body);
            }
        }

        MaterialData.loadMaterials(matFiles, registry);

        // All four materials present
        assertTrue(registry.get(id("orge:air")).isPresent(),          "orge:air should be present");
        assertTrue(registry.get(id("orge:water")).isPresent(),        "orge:water should be present");
        assertTrue(registry.get(id("orge:lava")).isPresent(),         "orge:lava should be present");
        assertTrue(registry.get(id("orge:generic_solid")).isPresent(),"orge:generic_solid should be present");

        // water.json extra fields
        Material water = registry.get(id("orge:water")).get();
        assertEquals(Identifier.parse("orge:ice"),
                water.minTarget(),
                "water.minTarget should be the orge:ice MATERIAL id");
        assertEquals(Identifier.parse("orge:steam"),
                water.maxTarget(),
                "water.maxTarget should be orge:steam");
        assertEquals(273.15f, water.minTemp(), 0.01f,
                "water.minTemp should be 273.15 K");
        assertEquals(373.15f, water.maxTemp(), 0.01f,
                "water.maxTemp should be 373.15 K");

        // lava.json extra fields
        Material lava = registry.get(id("orge:lava")).get();
        assertEquals(Identifier.parse("orge:stone"),
                lava.minTarget(),
                "lava.minTarget should be the orge:stone MATERIAL id");
    }
}
