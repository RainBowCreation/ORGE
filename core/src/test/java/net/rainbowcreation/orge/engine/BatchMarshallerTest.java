package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchMarshallerTest {

    @Test
    void flattenLaysOutSectionsContiguously() {
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        StepTask b = solidSection(new SubchunkKey(1, 0, 0), 400f);
        BatchMarshaller.Flat f = BatchMarshaller.flatten(List.of(a, b), stdLut());

        assertEquals(2, f.n());
        assertEquals(2 * SEC_N, f.tIn().length);
        assertEquals(300f, f.tIn()[0]);              // section 0
        assertEquals(400f, f.tIn()[SEC_N]);          // section 1 starts at SEC_N
        assertEquals(2 * 6 * FACE, f.haloT().length);
        // LUT arrays mirror the material list order.
        assertArrayEquals(new float[]{0f, 100f}, f.lutCond());
        assertArrayEquals(new float[]{0f, 500f}, f.lutHeatCap());
    }

    @Test
    void sliceReconstructsPerSectionArrays() {
        float[] flat = new float[2 * SEC_N];
        java.util.Arrays.fill(flat, 0, SEC_N, 1f);
        java.util.Arrays.fill(flat, SEC_N, 2 * SEC_N, 2f);
        List<float[]> out = BatchMarshaller.slice(flat, 2);
        assertEquals(2, out.size());
        assertEquals(SEC_N, out.get(0).length);
        assertEquals(1f, out.get(0)[0]);
        assertEquals(2f, out.get(1)[0]);
    }

    @Test
    void rejectsWrongTemperatureLength() {
        StepTask bad = new StepTask(new SubchunkKey(0, 0, 0),
                fillChar(SEC_N, (char) 1), fillFloat(SEC_N, 1000f),
                fillFloat(SEC_N - 1, 300f), voidHalo());
        assertThrows(IllegalArgumentException.class,
                () -> BatchMarshaller.flatten(List.of(bad), stdLut()));
    }

    @Test
    void rejectsMaterialIndexOutsideLut() {
        StepTask bad = solidSection(new SubchunkKey(0, 0, 0), 300f);
        bad.matIx()[0] = (char) 99; // LUT has only 2 entries
        assertThrows(IllegalArgumentException.class,
                () -> BatchMarshaller.flatten(List.of(bad), stdLut()));
    }

    @Test
    void rejectsEmptyLut() {
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        assertThrows(IllegalArgumentException.class,
                () -> BatchMarshaller.flatten(List.of(a), List.<Material>of()));
    }
}
