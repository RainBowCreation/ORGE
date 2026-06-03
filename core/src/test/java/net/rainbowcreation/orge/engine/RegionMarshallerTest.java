package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionMarshallerTest {
    @Test
    void flattenAndSliceRoundTrips() {
        int N = RegionMarshaller.CHUNK_N;
        char[] mi = new char[N]; float[] ms = new float[N]; float[] tt = new float[N];
        mi[idx(3, 70 + 64, 5)] = 1; ms[idx(3, 70 + 64, 5)] = 1000f; tt[idx(3, 70 + 64, 5)] = 350f;
        ColumnTask a = new ColumnTask(2, -1, mi, ms, tt);
        ColumnTask b = new ColumnTask(7, 4, new char[N], new float[N], new float[N]);

        RegionMarshaller.Flat f = RegionMarshaller.flatten(List.of(a, b));
        assertEquals(2, f.nCols());
        assertArrayEquals(new int[]{2, 7}, f.cx());
        assertArrayEquals(new int[]{-1, 4}, f.cz());
        assertEquals(1, f.matIx()[idx(3, 134, 5)]);     // column 0 at engine y=134
        assertEquals(1000f, f.mass()[idx(3, 134, 5)]);

        List<ColumnResult> r = RegionMarshaller.slice(f.matIx(), f.mass(), f.tIn(), 2);
        assertEquals(1, r.get(0).matIx()[idx(3, 134, 5)]);
        assertEquals(350f, r.get(0).temperature()[idx(3, 134, 5)]);
    }

    private static int idx(int x, int y, int z) { return x + 16 * y + 6144 * z; }
}
