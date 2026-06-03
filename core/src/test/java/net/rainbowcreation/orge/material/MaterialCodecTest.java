package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link MaterialCodec} under the canonical strict schema.
 * All tests decode via {@code MaterialCodec.fromJson(id, jsonElement)}.
 */
class MaterialCodecTest {

    private static final Identifier TEST_ID = Identifier.fromNamespaceAndPath("orge", "test_stone");

    // -------------------------------------------------------------------------
    // (a) Full decode: required fields + optionals present
    // -------------------------------------------------------------------------
    @Test
    void fullJsonDecodesAllFields() {
        String json = """
                {
                  "thermal_conductivity": 2.5,
                  "heat_capacity": 840.0,
                  "molar_mass": 0.060,
                  "default_mass": 2700.0,
                  "default_temperature": 290.0,
                  "viscosity": 0.001,
                  "max_temp": 3000.0,
                  "min_temp": 1600.0,
                  "max_target": "orge:lava",
                  "min_target": "orge:basalt",
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
        assertEquals(290.0f, m.defaultTemperature(), 1e-5f);
        assertEquals(3000.0f, m.maxTemp(), 1e-5f);
        assertEquals(1600.0f, m.minTemp(), 1e-5f);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "lava"), m.maxTarget());
        assertEquals(Identifier.fromNamespaceAndPath("orge", "basalt"), m.minTarget());
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "stone"), m.representativeBlock());
    }

    // -------------------------------------------------------------------------
    // (b) Minimal JSON: only the 5 required fields — defaults fill the rest
    // -------------------------------------------------------------------------
    @Test
    void minimalJsonAppliesDefaults() {
        String json = """
                {
                  "thermal_conductivity": 1.0,
                  "heat_capacity": 500.0,
                  "molar_mass": 0.018,
                  "default_mass": 1000.0,
                  "default_temperature": 290.0
                }
                """;

        Material m = MaterialCodec.fromJson(TEST_ID, JsonParser.parseString(json));

        assertNotNull(m);
        assertEquals(TEST_ID, m.id());
        assertEquals(1.0f, m.thermalConductivity(), 1e-6f);
        assertEquals(500.0f, m.heatCapacity(), 1e-3f);
        assertEquals(0.018f, m.molarMass(), 1e-6f);
        assertEquals(1000.0f, m.defaultMass(), 1e-3f);
        assertEquals(290.0f, m.defaultTemperature(), 1e-3f);

        // Optional fields — documented canonical defaults
        assertTrue(Float.isInfinite(m.viscosity()) && m.viscosity() > 0,
                "viscosity default should be +Infinity (absent => frozen)");
        assertEquals(1000.0f, m.minMass(), 1e-3f, "min_mass default should be default_mass");
        assertEquals(1000.0f, m.maxMass(), 1e-3f, "max_mass default should be default_mass");
        assertTrue(Float.isInfinite(m.maxTemp()) && m.maxTemp() > 0,
                "max_temp default should be +Infinity");
        assertTrue(Float.isInfinite(m.minTemp()) && m.minTemp() < 0,
                "min_temp default should be -Infinity");

        // Nullable id fields — absent means null
        assertNull(m.maxTarget(),       "max_target absent → null");
        assertNull(m.minTarget(),      "min_target absent → null");
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "test_stone"), m.representativeBlock(),
                "representative_block absent → minecraft:<path> (canonical default)");
    }

    // -------------------------------------------------------------------------
    // (c) Partial targets: max_temp + max_target present, no min phase
    // -------------------------------------------------------------------------
    @Test
    void gasLikeJsonDecodesPartialTargets() {
        String json = """
                {
                  "thermal_conductivity": 0.025,
                  "heat_capacity": 1005.0,
                  "molar_mass": 0.029,
                  "default_mass": 1.2,
                  "default_temperature": 290.0,
                  "max_temp": 373.15,
                  "max_target": "orge:steam"
                }
                """;

        Material m = MaterialCodec.fromJson(TEST_ID, JsonParser.parseString(json));

        assertNotNull(m);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "steam"), m.maxTarget(),
                "max_target should be orge:steam");
        assertNull(m.minTarget(), "min_target absent → null");
        assertEquals(373.15f, m.maxTemp(), 0.01f);
        // min_temp not supplied → -Infinity default
        assertTrue(Float.isInfinite(m.minTemp()) && m.minTemp() < 0,
                "min_temp default should be -Infinity");
    }

    // -------------------------------------------------------------------------
    // (d) Missing required field → decode throws (not silently wrong)
    // -------------------------------------------------------------------------
    @Test
    void missingRequiredFieldThrows() {
        // thermal_conductivity is missing
        String json = """
                {
                  "heat_capacity": 500.0,
                  "molar_mass": 0.018,
                  "default_mass": 1000.0,
                  "default_temperature": 290.0
                }
                """;

        assertThrows(IllegalArgumentException.class,
                () -> MaterialCodec.fromJson(TEST_ID, JsonParser.parseString(json)),
                "Missing required field should throw");
    }
}
