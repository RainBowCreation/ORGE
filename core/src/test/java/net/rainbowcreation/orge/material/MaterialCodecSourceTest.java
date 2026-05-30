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
                { "thermal_conductivity": 1.5, "heat_capacity": 1450, "default_mass": 3100,
                  "default_temperature": 1400, "pinned": true }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(1400f, m.defaultTemperature(), 1e-5f);
        assertTrue(m.pinned());
    }

    @Test
    void defaultsWhenAbsent() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertFalse(m.hasDefaultTemperature());
        assertFalse(m.pinned());
    }

    @Test
    void pinnedWithoutDefaultTemperatureThrows() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000,
                  "pinned": true }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> MaterialCodec.fromJson(ID, JsonParser.parseString(json)));
    }
}
