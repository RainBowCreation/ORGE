package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialCodecSourceTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "src");

    @Test
    void parsesDefaultTemperatureAndPinned() {
        String json = """
                { "thermal_conductivity": 1.5, "heat_capacity": 1450, "molar_mass": 0.060,
                  "default_mass": 3100, "default_temperature": 1400, "pinned": true }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(1400f, m.defaultTemperature(), 1e-5f);
        assertTrue(m.hasDefaultTemperature());
        assertTrue(m.pinned());
    }

    @Test
    void pinnedDefaultsFalseWhenAbsent() {
        // default_temperature is now REQUIRED on every material; pinned is the only one that defaults.
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "molar_mass": 0.018,
                  "default_mass": 1000, "default_temperature": 290 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertTrue(m.hasDefaultTemperature(), "default_temperature is required and present");
        assertFalse(m.pinned(), "pinned absent => false");
    }

    @Test
    void missingDefaultTemperatureThrows() {
        // default_temperature is required: absent => codec error, even without pinned.
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "molar_mass": 0.018,
                  "default_mass": 1000, "pinned": true }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> MaterialCodec.fromJson(ID, JsonParser.parseString(json)));
    }
}
