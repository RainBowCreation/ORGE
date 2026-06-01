package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Movability is the single derived test now ({@code movable() ⟺ viscosity finite}); the old
 * {@code fluid()}/{@code state} flags are gone. Absent viscosity loads as +INF (frozen).
 */
class MaterialFluidTest {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "w");

    @Test
    void legacyConstructorsDefaultImmovable() {
        Material m = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
        assertFalse(m.movable());
        Material n = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null, 300f, false);
        assertFalse(n.movable());
    }

    @Test
    void codecViscosityMakesMovable() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "molar_mass": 0.018,
                  "default_mass": 1000, "default_temperature": 290, "viscosity": 0.001 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertTrue(m.movable());
        assertEquals(0.001f, m.viscosity(), 1e-6f);
    }

    @Test
    void codecDefaultsImmovable() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "molar_mass": 0.018,
                  "default_mass": 1000, "default_temperature": 290 }
                """;
        assertFalse(MaterialCodec.fromJson(ID, JsonParser.parseString(json)).movable());
    }
}
