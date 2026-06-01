package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical three-mass fields ({@code min_mass}/{@code max_mass}). The old {@code state}/{@code gas}/
 * {@code min_flow_mass} model is gone: absent {@code min_mass}/{@code max_mass} both fall back to
 * {@code default_mass} (spec default), and movability is the single derived test. The codec reads the
 * canonical {@code min_mass} key.
 */
class MaterialThreeMassTest {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "w");

    @Test
    void legacyConstructorsDefaultThreeMassFields() {
        // 11-arg COMPAT ctor preserves the OLD positional semantics: min_mass defaults to 0 (legacy
        // min_flow_mass default), max_mass to default_mass (legacy maxMass() fallback). The material is
        // frozen (immovable) — absent viscosity is +INF. (The builder/codec default for min_mass is
        // default_mass; the compat ctor deliberately differs to keep old call sites byte-identical.)
        Material m = new Material(ID, 1f, 2f, 0f, 1000f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
        assertEquals(0f, m.minMass(), 0f, "compat ctor: min_mass defaults to 0 (legacy min_flow_mass)");
        assertEquals(1000f, m.maxMass(), 0f, "max_mass defaults to default_mass");
        assertFalse(m.movable());
    }

    @Test
    void codecParsesThreeMassFields() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "molar_mass": 0.018,
                  "default_mass": 1000, "default_temperature": 290, "viscosity": 0.001,
                  "min_mass": 125, "max_mass": 1000 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(125f, m.minMass(), 1e-6f);
        assertEquals(1000f, m.maxMass(), 1e-6f);
        assertTrue(m.movable(), "explicit viscosity makes it movable");
    }

    @Test
    void codecDefaultsMaxMassToDefaultMass() {
        String json = """
                { "thermal_conductivity": 0.025, "heat_capacity": 2080, "molar_mass": 0.018,
                  "default_mass": 0.6, "default_temperature": 400, "viscosity": 0.0, "min_mass": 0.6 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertTrue(m.movable());
        assertEquals(0.6f, m.minMass(), 1e-6f);
        // max_mass absent -> falls back to default_mass (0.6) so max_mass == default_mass holds.
        assertEquals(0.6f, m.maxMass(), 1e-6f);
    }

    @Test
    void codecDefaultsWhenAbsent() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.060,
                  "default_mass": 2500, "default_temperature": 290 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(2500f, m.minMass(), 0f, "absent min_mass -> default_mass");
        assertEquals(2500f, m.maxMass(), 0f, "absent max_mass -> default_mass");
        assertFalse(m.movable(), "absent viscosity -> frozen");
    }
}
