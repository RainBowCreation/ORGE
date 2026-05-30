package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class AmbientSeederTest {

    private static Material src(float t) {
        return new Material(Identifier.fromNamespaceAndPath("orge", "lava"),
                1f, 1f, 0f, 100f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, t, true);
    }
    private static Material bulk() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "air"),
                1f, 1f, 0f, 1f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null);
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
