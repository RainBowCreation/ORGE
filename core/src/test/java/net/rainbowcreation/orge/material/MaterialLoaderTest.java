package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict required-or-error codec tests (Task 1.2). Exercises the canonical schema:
 * five required fields (each errors by name when absent), optional defaults, the
 * {@code min_temp⇒min_target}/{@code max_temp⇒max_target} pairing rule, material-id
 * targets, and the {@code viscosity 0} (fastest) vs absent (frozen) distinction.
 *
 * <p>All JSON here is synthetic — these tests never touch the bundled material files.</p>
 */
class MaterialLoaderTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "test_mat");

    private static Material parse(String json) {
        return MaterialCodec.fromJson(ID, JsonParser.parseString(json));
    }

    // -------------------------------------------------------------------------
    // (a) each of the 5 required fields is required: absence throws, naming it + the id
    // -------------------------------------------------------------------------

    @Test
    void missingThermalConductivityThrowsNamingField() {
        String json = """
                { "heat_capacity": 840, "molar_mass": 0.06, "default_mass": 2500,
                  "default_temperature": 290 }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(ex.getMessage().contains("thermal_conductivity"),
                "message names the missing field, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("orge:test_mat"),
                "message names the material id, got: " + ex.getMessage());
    }

    @Test
    void missingHeatCapacityThrowsNamingField() {
        String json = """
                { "thermal_conductivity": 2.0, "molar_mass": 0.06, "default_mass": 2500,
                  "default_temperature": 290 }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(ex.getMessage().contains("heat_capacity"), ex.getMessage());
        assertTrue(ex.getMessage().contains("orge:test_mat"), ex.getMessage());
    }

    @Test
    void missingMolarMassThrowsNamingField() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "default_mass": 2500,
                  "default_temperature": 290 }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(ex.getMessage().contains("molar_mass"), ex.getMessage());
        assertTrue(ex.getMessage().contains("orge:test_mat"), ex.getMessage());
    }

    @Test
    void missingDefaultMassThrowsNamingField() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_temperature": 290 }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(ex.getMessage().contains("default_mass"), ex.getMessage());
        assertTrue(ex.getMessage().contains("orge:test_mat"), ex.getMessage());
    }

    @Test
    void missingDefaultTemperatureThrowsNamingField() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_mass": 2500 }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(ex.getMessage().contains("default_temperature"), ex.getMessage());
        assertTrue(ex.getMessage().contains("orge:test_mat"), ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // (b) optional absent => builder defaults
    // -------------------------------------------------------------------------

    @Test
    void optionalAbsentAppliesBuilderDefaults() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_mass": 2500, "default_temperature": 290 }
                """;
        Material m = parse(json);
        assertTrue(Float.isInfinite(m.viscosity()) && m.viscosity() > 0,
                "absent viscosity => +Infinity (frozen)");
        assertFalse(m.movable(), "frozen => not movable");
        assertEquals(2500f, m.minMass(), 0f, "absent min_mass => default_mass");
        assertEquals(2500f, m.maxMass(), 0f, "absent max_mass => default_mass");
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "air"), m.representativeBlock(),
                "absent representative_block => minecraft:air");
        assertFalse(m.pinned(), "absent pinned => false");
        assertNull(m.minTarget());
        assertNull(m.maxTarget());
    }

    // -------------------------------------------------------------------------
    // (c) temp⇒target pairing rule
    // -------------------------------------------------------------------------

    @Test
    void minTempWithoutMinTargetThrows() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_mass": 2500, "default_temperature": 290, "min_temp": 270 }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(ex.getMessage().contains("min_target"),
                "message names the required pairing field, got: " + ex.getMessage());
    }

    @Test
    void maxTempWithoutMaxTargetThrows() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_mass": 2500, "default_temperature": 290, "max_temp": 400 }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(ex.getMessage().contains("max_target"),
                "message names the required pairing field, got: " + ex.getMessage());
    }

    @Test
    void tempWithTargetParses() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_mass": 2500, "default_temperature": 290,
                  "min_temp": 270, "min_target": "orge:ice",
                  "max_temp": 400, "max_target": "orge:steam" }
                """;
        Material m = parse(json);
        assertEquals(270f, m.minTemp(), 0f);
        assertEquals(400f, m.maxTemp(), 0f);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "ice"), m.minTarget());
        assertEquals(Identifier.fromNamespaceAndPath("orge", "steam"), m.maxTarget());
    }

    // -------------------------------------------------------------------------
    // (d) targets parse as material Identifier (no minecraft:air defaulting hack)
    // -------------------------------------------------------------------------

    @Test
    void minTargetParsesAsMaterialIdentifier() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_mass": 2500, "default_temperature": 290,
                  "min_temp": 270, "min_target": "orge:ice" }
                """;
        Material m = parse(json);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "ice"), m.minTarget(),
                "min_target parses as the material id orge:ice");
    }

    @Test
    void maxTempWithTargetDoesNotDefaultToMinecraftAir() {
        // The old hack defaulted maxTarget to minecraft:air when max_temp was finite.
        // With the pairing rule, finite max_temp REQUIRES an explicit max_target; the
        // value we give must be honoured verbatim (no minecraft:air fallback).
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_mass": 2500, "default_temperature": 290,
                  "max_temp": 400, "max_target": "orge:steam" }
                """;
        Material m = parse(json);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "steam"), m.maxTarget(),
                "max_target is the declared material id, never minecraft:air");
    }

    // -------------------------------------------------------------------------
    // (e) viscosity 0 stays 0 (fastest); absent => +Infinity
    // -------------------------------------------------------------------------

    @Test
    void viscosityZeroStaysExactlyZero() {
        String json = """
                { "thermal_conductivity": 0.026, "heat_capacity": 1005, "molar_mass": 0.029,
                  "default_mass": 1.2, "default_temperature": 290, "viscosity": 0.0 }
                """;
        Material m = parse(json);
        assertEquals(0f, m.viscosity(), 0f, "viscosity 0 stays exactly 0 (fastest), never infinity");
        assertTrue(m.movable(), "viscosity 0 is finite => movable");
    }

    @Test
    void viscosityAbsentIsPositiveInfinity() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.06,
                  "default_mass": 2500, "default_temperature": 290 }
                """;
        Material m = parse(json);
        assertTrue(Float.isInfinite(m.viscosity()) && m.viscosity() > 0,
                "absent viscosity => +Infinity");
    }

    // -------------------------------------------------------------------------
    // min_mass is the canonical key (not min_flow_mass)
    // -------------------------------------------------------------------------

    @Test
    void minMassKeyIsRead() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "molar_mass": 0.018,
                  "default_mass": 1000, "default_temperature": 290,
                  "viscosity": 0.001, "min_mass": 125, "max_mass": 1000 }
                """;
        Material m = parse(json);
        assertEquals(125f, m.minMass(), 0f, "canonical min_mass key is read");
        assertEquals(1000f, m.maxMass(), 0f);
    }
}
