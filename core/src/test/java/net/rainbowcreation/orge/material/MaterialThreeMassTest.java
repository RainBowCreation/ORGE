package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialThreeMassTest {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "w");

    @Test
    void legacyConstructorsDefaultThreeMassFields() {
        // Back-compat ctor: min_flow_mass=0, max_mass falls back to default_mass, gas=false.
        Material m = new Material(ID, 1f, 2f, 0f, 1000f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
        assertEquals(0f, m.minFlowMass(), 0f);
        assertEquals(1000f, m.maxMass(), 0f, "max_mass defaults to default_mass");
        assertFalse(m.gas());
    }

    @Test
    void codecParsesThreeMassFields() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000,
                  "viscosity": 0.001, "state": "fluid",
                  "min_flow_mass": 125, "max_mass": 1000 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(125f, m.minFlowMass(), 1e-6f);
        assertEquals(1000f, m.maxMass(), 1e-6f);
        assertFalse(m.gas());
    }

    @Test
    void codecParsesGasTrueAndDefaultsMaxMassToDefaultMass() {
        String json = """
                { "thermal_conductivity": 0.025, "heat_capacity": 2080, "default_mass": 0.6,
                  "state": "gas", "min_flow_mass": 0.6 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertTrue(m.gas());
        assertEquals(0.6f, m.minFlowMass(), 1e-6f);
        // max_mass absent -> falls back to default_mass (0.6) so max_mass == default_mass holds.
        assertEquals(0.6f, m.maxMass(), 1e-6f);
    }

    @Test
    void codecDefaultsWhenAbsent() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "default_mass": 2500 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(0f, m.minFlowMass(), 0f);
        assertEquals(2500f, m.maxMass(), 0f, "absent max_mass -> default_mass");
        assertFalse(m.gas());
    }
}
