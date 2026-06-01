package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchMarshallerMassTest {
    @Test
    void flattenCarriesHaloMassAndSixPhysicsLut() {
        StepTask a = solidSection(new net.rainbowcreation.orge.section.SubchunkKey(0,0,0), 300f);
        List<Material> lut = List.of(
                // void: 0/0/0 masses, finite visc ⇒ displaceable.
                Material.builder(Identifier.fromNamespaceAndPath("orge","void"))
                        .thermalConductivity(0f).heatCapacity(1f).molarMass(0f)
                        .defaultMass(0f).defaultTemperature(Float.NaN)
                        .viscosity(0f).minMass(0f).maxMass(0f).build(),
                // water: movable (finite visc 0.001), min 125, max/default 1000.
                Material.builder(Identifier.fromNamespaceAndPath("orge","water"))
                        .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                        .defaultMass(1000f).defaultTemperature(Float.NaN)
                        .viscosity(0.001f).minMass(125f).maxMass(1000f).build());
        BatchMarshaller.Flat f = BatchMarshaller.flatten(List.of(a), lut);
        assertEquals(1 * BatchMarshaller.FACES * BatchMarshaller.FACE, f.haloMass().length);
        assertEquals(2, f.lutVisc().length);
        // Re-pointed from the dropped fullMass/fluid flag arrays to the six-physics model:
        // movability is visc finite (water) vs +∞ (frozen); fullMass replaced by minMass/maxMass.
        assertEquals(0.001f, f.lutVisc()[1], 1e-6f, "water movable ⇒ finite visc");
        assertEquals(125f, f.lutMinMass()[1], 1e-4f);
        assertEquals(1000f, f.lutMaxMass()[1], 0f);
        assertTrue(Float.isFinite(f.lutVisc()[0]), "void displaceable ⇒ finite visc");
    }

    @Test
    void sliceMassSplitsPerSection() {
        float[] flat = new float[2 * BatchMarshaller.SEC_N];
        flat[0] = 11f; flat[BatchMarshaller.SEC_N] = 22f;
        List<float[]> per = BatchMarshaller.sliceMass(flat, 2);
        assertEquals(11f, per.get(0)[0], 0f);
        assertEquals(22f, per.get(1)[0], 0f);
    }
}
