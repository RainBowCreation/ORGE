package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialFluidTest {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "w");

    @Test
    void legacyConstructorsDefaultFluidFalse() {
        Material m = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
        assertFalse(m.fluid());
        Material n = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null, 300f, false);
        assertFalse(n.fluid());
    }

    @Test
    void codecParsesFluidTrue() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000,
                  "viscosity": 0.001, "fluid": true }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertTrue(m.fluid());
        assertEquals(0.001f, m.viscosity(), 1e-6f);
    }

    @Test
    void codecDefaultsFluidFalse() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000 }
                """;
        assertFalse(MaterialCodec.fromJson(ID, JsonParser.parseString(json)).fluid());
    }
}
