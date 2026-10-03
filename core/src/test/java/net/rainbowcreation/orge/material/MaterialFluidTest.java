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

    @Test
    void finiteViscositySolidWithYieldStressIsNotMovable() {
        // Law #8 v4.4: natural solids carry a finite post-yield μ plus a large τ_y. Its own cell weight
        // (defaultMass·g) never beats τ_y, so stone/ice are not movable at rest (never fluid-painted).
        String stone = """
                { "thermal_conductivity": 2.5, "heat_capacity": 800, "molar_mass": 0.065,
                  "default_mass": 2700, "default_temperature": 290, "viscosity": 1e9, "yield_stress": 1e8 }
                """;
        String ice = """
                { "thermal_conductivity": 2.2, "heat_capacity": 2108, "molar_mass": 0.018,
                  "default_mass": 917, "default_temperature": 270, "viscosity": 1e4, "yield_stress": 1e5 }
                """;
        assertFalse(MaterialCodec.fromJson(ID, JsonParser.parseString(stone)).movable());
        assertFalse(MaterialCodec.fromJson(ID, JsonParser.parseString(ice)).movable());
        // a weak granular τ_y below the cell's own weight (100 kg · 10 = 1000 N) still flows
        String sand = """
                { "thermal_conductivity": 0.3, "heat_capacity": 800, "molar_mass": 0.06,
                  "default_mass": 100, "default_temperature": 290, "viscosity": 10, "yield_stress": 500 }
                """;
        assertTrue(MaterialCodec.fromJson(ID, JsonParser.parseString(sand)).movable());
    }
}
