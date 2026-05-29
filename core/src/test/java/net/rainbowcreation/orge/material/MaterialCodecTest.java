package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link MaterialCodec}.
 * All tests decode via {@code MaterialCodec.fromJson(id, jsonElement)}.
 */
class MaterialCodecTest {

    private static final Identifier TEST_ID = Identifier.fromNamespaceAndPath("orge", "test_stone");

    // -------------------------------------------------------------------------
    // (a) Full decode: all 10 body fields present
    // -------------------------------------------------------------------------
    @Test
    void fullJsonDecodesAllFields() {
        String json = """
                {
                  "thermal_conductivity": 2.5,
                  "heat_capacity": 840.0,
                  "viscosity": 0.001,
                  "default_mass": 2700.0,
                  "molar_mass": 0.060,
                  "boiling_point": 3000.0,
                  "freezing_point": 1600.0,
                  "boiling_target": "orge:lava",
                  "freezing_target": "orge:basalt",
                  "representative_block": "minecraft:stone"
                }
                """;

        Material m = MaterialCodec.fromJson(TEST_ID, JsonParser.parseString(json));

        assertNotNull(m, "fromJson should not return null");
        assertEquals(TEST_ID, m.id());
        assertEquals(2.5f, m.thermalConductivity(), 1e-5f);
        assertEquals(840.0f, m.heatCapacity(), 1e-5f);
        assertEquals(0.001f, m.viscosity(), 1e-5f);
        assertEquals(2700.0f, m.defaultMass(), 1e-5f);
        assertEquals(0.060f, m.molarMass(), 1e-5f);
        assertEquals(3000.0f, m.boilingPoint(), 1e-5f);
        assertEquals(1600.0f, m.freezingPoint(), 1e-5f);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "lava"), m.boilingTarget());
        assertEquals(Identifier.fromNamespaceAndPath("orge", "basalt"), m.freezingTarget());
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "stone"), m.representativeBlock());
    }

    // -------------------------------------------------------------------------
    // (b) Minimal JSON: only the 3 required fields — defaults fill the rest
    // -------------------------------------------------------------------------
    @Test
    void minimalJsonAppliesDefaults() {
        String json = """
                {
                  "thermal_conductivity": 1.0,
                  "heat_capacity": 500.0,
                  "default_mass": 1000.0
                }
                """;

        Material m = MaterialCodec.fromJson(TEST_ID, JsonParser.parseString(json));

        assertNotNull(m);
        assertEquals(TEST_ID, m.id());
        assertEquals(1.0f, m.thermalConductivity(), 1e-6f);
        assertEquals(500.0f, m.heatCapacity(), 1e-3f);
        assertEquals(1000.0f, m.defaultMass(), 1e-3f);

        // Optional fields — documented defaults
        assertEquals(0f, m.viscosity(), 1e-6f,        "viscosity default should be 0");
        assertEquals(0f, m.molarMass(), 1e-6f,        "molar_mass default should be 0");
        assertTrue(Float.isInfinite(m.boilingPoint()) && m.boilingPoint() > 0,
                "boiling_point default should be +Infinity");
        assertTrue(Float.isInfinite(m.freezingPoint()) && m.freezingPoint() < 0,
                "freezing_point default should be -Infinity");

        // Nullable id fields — absent means null
        assertNull(m.boilingTarget(),       "boiling_target absent → null");
        assertNull(m.freezingTarget(),      "freezing_target absent → null");
        assertNull(m.representativeBlock(), "representative_block absent → null");
    }

    // -------------------------------------------------------------------------
    // (c) Gas-like: has boiling_target but no freezing_target
    // -------------------------------------------------------------------------
    @Test
    void gasLikeJsonDecodesPartialTargets() {
        String json = """
                {
                  "thermal_conductivity": 0.025,
                  "heat_capacity": 1005.0,
                  "default_mass": 1.2,
                  "boiling_point": 373.15,
                  "boiling_target": "orge:steam"
                }
                """;

        Material m = MaterialCodec.fromJson(TEST_ID, JsonParser.parseString(json));

        assertNotNull(m);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "steam"), m.boilingTarget(),
                "boiling_target should be orge:steam");
        assertNull(m.freezingTarget(), "freezing_target absent → null");
        assertEquals(373.15f, m.boilingPoint(), 0.01f);
        // freezing_point not supplied → -Infinity default
        assertTrue(Float.isInfinite(m.freezingPoint()) && m.freezingPoint() < 0,
                "freezing_point default should be -Infinity");
    }

    // -------------------------------------------------------------------------
    // (d) Missing required field → decode throws / returns null (not silently wrong)
    // -------------------------------------------------------------------------
    @Test
    void missingRequiredFieldThrows() {
        // thermal_conductivity is missing
        String json = """
                {
                  "heat_capacity": 500.0,
                  "default_mass": 1000.0
                }
                """;

        assertThrows(IllegalArgumentException.class,
                () -> MaterialCodec.fromJson(TEST_ID, JsonParser.parseString(json)),
                "Missing required field should throw");
    }
}
