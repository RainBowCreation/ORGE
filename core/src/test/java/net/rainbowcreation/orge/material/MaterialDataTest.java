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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link MaterialData}.
 */
class MaterialDataTest {

    private MaterialRegistry registry;
    private MaterialBindings bindings;

    /** Fake tag membership: tagId → set of blockIds. */
    private final Map<Identifier, Set<Identifier>> tagMembers = new HashMap<>();
    private final MaterialBindings.TagMembership fakeTags =
            (tagId, blockId) -> tagMembers.getOrDefault(tagId, Set.of()).contains(blockId);

    private static Identifier id(String full) {
        return Identifier.parse(full);
    }

    @BeforeEach
    void setUp() {
        registry = new MaterialRegistry();
        bindings = new MaterialBindings();
        tagMembers.clear();
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
                  "default_mass": 1000.0,
                  "molar_mass": 0.018
                }
                """;
        String ironJson = """
                {
                  "thermal_conductivity": 80.0,
                  "heat_capacity": 450.0,
                  "default_mass": 7874.0,
                  "molar_mass": 0.056
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
    // (b) loadBindings: in-memory file list with tags + overrides
    // -------------------------------------------------------------------------

    @Test
    void loadBindings_populatesOverridesAndTagsInOrder() {
        // Two tags in known order; two overrides
        String bindingsJson = """
                {
                  "tags": [
                    { "tag": "c:stones",   "material": "orge:generic_solid" },
                    { "tag": "c:metals",   "material": "orge:iron" }
                  ],
                  "overrides": {
                    "minecraft:water": "orge:water",
                    "minecraft:lava":  "orge:lava"
                  }
                }
                """;

        MaterialData.loadBindings(List.of(JsonParser.parseString(bindingsJson)), bindings);

        // Override: minecraft:water → orge:water
        Identifier waterBlock = id("minecraft:water");
        Identifier lavaBlock  = id("minecraft:lava");
        assertEquals(id("orge:water"), bindings.materialFor(waterBlock, fakeTags),
                "override: minecraft:water → orge:water");
        assertEquals(id("orge:lava"), bindings.materialFor(lavaBlock, fakeTags),
                "override: minecraft:lava → orge:lava");

        // Tag ordering: a block that is in BOTH c:stones and c:metals should resolve to generic_solid
        // because c:stones was registered first
        Identifier stoneBlock = id("minecraft:stone");
        tagMembers.put(id("c:stones"), Set.of(stoneBlock));
        tagMembers.put(id("c:metals"), Set.of(stoneBlock));

        assertEquals(id("orge:generic_solid"), bindings.materialFor(stoneBlock, fakeTags),
                "first tag (c:stones) should win over second (c:metals)");
    }

    @Test
    void loadBindings_toleratesMissingTagsKey() {
        String bindingsJson = """
                {
                  "overrides": {
                    "minecraft:air": "orge:air"
                  }
                }
                """;

        // Should not throw
        assertDoesNotThrow(() ->
                MaterialData.loadBindings(List.of(JsonParser.parseString(bindingsJson)), bindings));

        assertEquals(id("orge:air"),
                bindings.materialFor(id("minecraft:air"), fakeTags),
                "override should still resolve with no tags key");
    }

    @Test
    void loadBindings_toleratesMissingOverridesKey() {
        String bindingsJson = """
                {
                  "tags": [
                    { "tag": "c:stones", "material": "orge:generic_solid" }
                  ]
                }
                """;

        assertDoesNotThrow(() ->
                MaterialData.loadBindings(List.of(JsonParser.parseString(bindingsJson)), bindings));

        // Tag binding should still work
        Identifier cobble = id("minecraft:cobblestone");
        tagMembers.put(id("c:stones"), Set.of(cobble));
        assertEquals(id("orge:generic_solid"), bindings.materialFor(cobble, fakeTags),
                "tag binding should work even with no overrides key");
    }

    // -------------------------------------------------------------------------
    // Fix 1 — descriptive error on malformed tag-binding entry
    // -------------------------------------------------------------------------

    @Test
    void loadBindings_missingMaterialKey_throwsIllegalArgumentException() {
        String bindingsJson = """
                {
                  "tags": [ { "tag": "c:stones" } ]
                }
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MaterialData.loadBindings(
                        List.of(JsonParser.parseString(bindingsJson)), bindings));
        assertTrue(ex.getMessage().contains("material"),
                "exception message should name the missing key 'material', got: " + ex.getMessage());
    }

    @Test
    void loadBindings_missingTagKey_throwsIllegalArgumentException() {
        String bindingsJson = """
                {
                  "tags": [ { "material": "orge:generic_solid" } ]
                }
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MaterialData.loadBindings(
                        List.of(JsonParser.parseString(bindingsJson)), bindings));
        assertTrue(ex.getMessage().contains("tag"),
                "exception message should name the missing key 'tag', got: " + ex.getMessage());
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
                  "default_mass": 7874.0
                  // molar_mass intentionally omitted — it is optional and defaults to 0
                }
                """;
        // Missing required field "heat_capacity" → MaterialCodec will throw
        String badJson = """
                {
                  "thermal_conductivity": 1.0,
                  "default_mass": 100.0
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

    @Test
    void loadBindings_multipleFilesAreMerged() {
        // Two separate binding files — both contribute their entries
        String file1 = """
                {
                  "overrides": { "minecraft:water": "orge:water" }
                }
                """;
        String file2 = """
                {
                  "overrides": { "minecraft:lava": "orge:lava" }
                }
                """;

        MaterialData.loadBindings(
                List.of(JsonParser.parseString(file1), JsonParser.parseString(file2)),
                bindings);

        assertEquals(id("orge:water"), bindings.materialFor(id("minecraft:water"), fakeTags));
        assertEquals(id("orge:lava"),  bindings.materialFor(id("minecraft:lava"),  fakeTags));
    }

    // -------------------------------------------------------------------------
    // Fix 3 — wrap malformed Identifier parse errors with context
    // -------------------------------------------------------------------------

    @Test
    void loadBindings_malformedTagId_throwsIllegalArgumentExceptionWithContext() {
        // "c:stones and gravel" — space is an illegal character for Identifier
        String bindingsJson = """
                {
                  "tags": [
                    { "tag": "c:stones and gravel", "material": "orge:generic_solid" }
                  ]
                }
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MaterialData.loadBindings(
                        List.of(JsonParser.parseString(bindingsJson)), bindings));
        assertTrue(ex.getMessage().contains("c:stones and gravel"),
                "exception message should contain the offending tag id, got: " + ex.getMessage());
    }

    @Test
    void loadBindings_malformedOverrideValueId_throwsIllegalArgumentExceptionWithContext() {
        // "orge:not a material" — space is an illegal character for Identifier
        String bindingsJson = """
                {
                  "overrides": { "minecraft:water": "orge:not a material" }
                }
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MaterialData.loadBindings(
                        List.of(JsonParser.parseString(bindingsJson)), bindings));
        assertTrue(ex.getMessage().contains("orge:not a material"),
                "exception message should contain the offending material id, got: " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // (c) Real-resource test: default JSON files from the classpath
    // -------------------------------------------------------------------------

    @Test
    void realResourceTest_defaultMaterialsAndBindings() throws Exception {
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
        assertEquals(Identifier.parse("minecraft:ice"),
                water.freezingTarget(),
                "water.freezingTarget should be minecraft:ice");
        assertEquals(Identifier.parse("orge:steam"),
                water.boilingTarget(),
                "water.boilingTarget should be orge:steam");
        assertEquals(273.15f, water.freezingPoint(), 0.01f,
                "water.freezingPoint should be 273.15 K");
        assertEquals(373.15f, water.boilingPoint(), 0.01f,
                "water.boilingPoint should be 373.15 K");

        // lava.json extra fields
        Material lava = registry.get(id("orge:lava")).get();
        assertEquals(Identifier.parse("minecraft:stone"),
                lava.freezingTarget(),
                "lava.freezingTarget should be minecraft:stone");

        // Load and test default bindings
        String bindingsPath = "/data/orge/orge/bindings/default.json";
        try (InputStream is = getClass().getResourceAsStream(bindingsPath)) {
            assertNotNull(is, "Default bindings resource must exist: " + bindingsPath);
            JsonElement bindingsBody = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            MaterialData.loadBindings(List.of(bindingsBody), bindings);
        }

        // minecraft:water → orge:water via override
        assertEquals(id("orge:water"),
                bindings.materialFor(id("minecraft:water"), fakeTags),
                "default bindings: minecraft:water should resolve to orge:water");

        // minecraft:air → orge:air via override
        assertEquals(id("orge:air"),
                bindings.materialFor(id("minecraft:air"), fakeTags),
                "default bindings: minecraft:air should resolve to orge:air");

        // minecraft:lava → orge:lava via override
        assertEquals(id("orge:lava"),
                bindings.materialFor(id("minecraft:lava"), fakeTags),
                "default bindings: minecraft:lava should resolve to orge:lava");
    }
}
