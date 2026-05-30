package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialFieldsTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "t");

    @Test
    void legacyElevenArgConstructorDefaultsNewFields() {
        Material m = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
        assertTrue(Float.isNaN(m.defaultTemperature()));
        assertFalse(m.pinned());
        assertFalse(m.hasDefaultTemperature());
    }

    @Test
    void fullConstructorCarriesNewFields() {
        Material m = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null,
                1400f, true);
        assertEquals(1400f, m.defaultTemperature(), 1e-5f);
        assertTrue(m.pinned());
        assertTrue(m.hasDefaultTemperature());
    }
}
