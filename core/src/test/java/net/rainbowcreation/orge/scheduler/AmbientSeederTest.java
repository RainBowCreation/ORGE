package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class AmbientSeederTest {

    private static Material src(float t) {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "lava"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(100f).defaultTemperature(t).pinned(true)
                .build();
    }
    private static Material bulk() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "air"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(1f).defaultTemperature(Float.NaN)
                .build();
    }

    @Test
    void sourceCellsGetDefaultTemperatureBulkGetsAmbient() {
        // cell 0 is a source at 1400; all others are bulk -> ambient 290.
        IntFunction<Material> cells = i -> (i == 0) ? src(1400f) : bulk();
        float[] t = AmbientSeeder.seed(cells, 290f);
        assertEquals(SectionData.CELLS, t.length);
        assertEquals(1400f, t[0], 1e-4f);
        assertEquals(290f, t[1], 1e-4f);
        assertEquals(290f, t[SectionData.CELLS - 1], 1e-4f);
    }
}
