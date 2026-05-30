package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchMarshallerMassTest {
    @Test
    void flattenCarriesHaloMassAndFluidLut() {
        StepTask a = solidSection(new net.rainbowcreation.orge.section.SubchunkKey(0,0,0), 300f);
        List<Material> lut = List.of(
                new Material(Identifier.fromNamespaceAndPath("orge","void"), 0f,1f,0f,0f,0f,
                        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null),
                new Material(Identifier.fromNamespaceAndPath("orge","water"), 0.6f,4186f,0.001f,1000f,0.018f,
                        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null, Float.NaN, false, true));
        BatchMarshaller.Flat f = BatchMarshaller.flatten(List.of(a), lut);
        assertEquals(1 * BatchMarshaller.FACES * BatchMarshaller.FACE, f.haloMass().length);
        assertEquals(2, f.lutVisc().length);
        assertEquals(0.001f, f.lutVisc()[1], 1e-6f);
        assertEquals(1000f, f.lutFullMass()[1], 0f);
        assertEquals((byte) 1, f.lutFluid()[1]);
        assertEquals((byte) 0, f.lutFluid()[0]);
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
