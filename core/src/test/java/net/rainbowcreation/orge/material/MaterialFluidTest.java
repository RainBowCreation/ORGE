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
    void builderDefaultsImmovable() {
        // Re-pointed from deleted compat ctors: absent viscosity ⇒ +∞ (frozen/immovable).
        Material m = Material.builder(ID)
                .thermalConductivity(1f).heatCapacity(2f).molarMass(0f)
                .defaultMass(100f).defaultTemperature(Float.NaN)
                .build();
        assertFalse(m.movable());
        Material n = Material.builder(ID)
                .thermalConductivity(1f).heatCapacity(2f).molarMass(0f)
                .defaultMass(100f).defaultTemperature(300f)
                .build();
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
