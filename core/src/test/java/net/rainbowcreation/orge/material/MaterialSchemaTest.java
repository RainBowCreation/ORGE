package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1.1: the canonical {@link Material} schema. The record exposes EXACTLY the
 * required/optional fields, a {@code movable()} helper ("movable ⟺ viscosity finite"),
 * and a fluent {@link Material#builder(Identifier)} that applies absent-field defaults.
 * The old {@code state}/flag/{@code min_flow_mass} model is gone.
 */
class MaterialSchemaTest {

    private static Identifier id(String s) {
        return Identifier.parse(s);
    }

    private static boolean hasMethod(Class<?> c, String name) {
        for (var m : c.getMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }

    @Test
    void defaults_apply_when_optional_absent() {
        Material m = Material.builder(id("orge:stone"))
                .thermalConductivity(2.0f).heatCapacity(840f).molarMass(0.060f)
                .defaultMass(2500f).defaultTemperature(290f)
                .build();                              // no viscosity, no min/max_mass
        assertTrue(Float.isInfinite(m.viscosity()));          // absent => frozen (+INF)
        assertEquals(2500f, m.minMass(), 0f);                 // absent => default_mass
        assertEquals(2500f, m.maxMass(), 0f);                 // absent => default_mass
        assertEquals(id("minecraft:stone"), m.representativeBlock()); // absent => minecraft:<path>
        assertFalse(m.pinned());
    }

    @Test
    void no_state_no_flags_on_record() {
        for (String gone : new String[]{"state", "fluid", "gas", "air", "minFlowMass"})
            assertFalse(hasMethod(Material.class, gone), gone + " must be removed");
    }

    @Test
    void movable_iff_viscosity_finite() {
        Material frozen = Material.builder(id("orge:stone"))
                .thermalConductivity(2f).heatCapacity(840f).molarMass(0.06f)
                .defaultMass(2500f).defaultTemperature(290f)
                .build();                              // absent viscosity => +INF => not movable
        assertFalse(frozen.movable());

        Material flowing = Material.builder(id("orge:water"))
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(290f)
                .viscosity(0.001f)
                .build();
        assertTrue(flowing.movable());
    }

    @Test
    void explicit_optionals_are_honoured() {
        Material m = Material.builder(id("orge:water"))
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(290f)
                .viscosity(0.001f).minMass(125f).maxMass(1000f)
                .minTemp(273f).maxTemp(373f)
                .minTarget(id("orge:ice")).maxTarget(id("orge:steam"))
                .representativeBlock(id("minecraft:water")).pinned(true)
                .build();
        assertEquals(0.001f, m.viscosity(), 0f);
        assertEquals(125f, m.minMass(), 0f);
        assertEquals(1000f, m.maxMass(), 0f);
        assertEquals(273f, m.minTemp(), 0f);
        assertEquals(373f, m.maxTemp(), 0f);
        assertEquals(id("orge:ice"), m.minTarget());
        assertEquals(id("orge:steam"), m.maxTarget());
        assertEquals(id("minecraft:water"), m.representativeBlock());
        assertTrue(m.pinned());
    }
}
