package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialFieldsTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "t");

    // Re-pointed from the deleted legacy positional ctors to the canonical builder (Task 2.1).
    @Test
    void builderDefaultsNewFields() {
        Material m = Material.builder(ID)
                .thermalConductivity(1f).heatCapacity(2f).molarMass(0f)
                .defaultMass(100f).defaultTemperature(Float.NaN)
                .build();
        assertTrue(Float.isNaN(m.defaultTemperature()));
        assertFalse(m.pinned());
        assertFalse(m.hasDefaultTemperature());
    }

    @Test
    void builderCarriesTemperatureAndPin() {
        Material m = Material.builder(ID)
                .thermalConductivity(1f).heatCapacity(2f).molarMass(0f)
                .defaultMass(100f).defaultTemperature(1400f).pinned(true)
                .build();
        assertEquals(1400f, m.defaultTemperature(), 1e-5f);
        assertTrue(m.pinned());
        assertTrue(m.hasDefaultTemperature());
    }
}
