package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ColumnSectionCodec}'s base-offset slices — how a section reads composition slot {@code k} out
 *  of a column's slot channel ({@code base = (k−1)·CHUNK_N}, DESIGN-LAW v5 #7; issue #27). */
class ColumnSectionCodecTest {

    @Test
    void offsetSlicesReadTheChosenSlotOfTheColumnChannel() {
        int n = RegionMarshaller.CHUNK_N;
        float[] mass = new float[RegionMarshaller.SLOT_N];
        char[] ix = new char[RegionMarshaller.SLOT_N];
        int ci = ColumnSectionCodec.colIdx(3, -2, 9, 14);
        mass[ci] = 1f;          ix[ci] = 11;              // slot 1
        mass[n + ci] = 2f;      ix[n + ci] = 22;          // slot 2
        mass[2 * n + ci] = 3f;  ix[2 * n + ci] = 33;      // slot 3
        int si = 3 + 16 * 9 + 256 * 14;
        for (int k = 1; k < SectionData.SLOTS; k++) {
            int base = (k - 1) * n;
            float[] m = ColumnSectionCodec.sliceSectionChannel(mass, base, -2);
            char[] c = ColumnSectionCodec.sliceSectionMaterials(ix, base, -2);
            assertEquals(k, m[si], "slot " + k + " mass");
            assertEquals(11 * k, c[si], "slot " + k + " index");
            float sum = 0f;
            for (float v : m) sum += v;
            assertEquals(k, sum, "nothing else of slot " + k + " lands in the section");
        }
        assertEquals(1f, ColumnSectionCodec.sliceSectionChannel(mass, -2)[si], "base 0 = the plain slice");
        assertEquals(0f, ColumnSectionCodec.sliceSectionChannel(mass, n, -1)[si], "other section is empty");
    }
}
