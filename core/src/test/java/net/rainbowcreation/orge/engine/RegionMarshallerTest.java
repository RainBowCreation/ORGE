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

    /** E-LEDGER-THROUGH-THE-SEAM: the absolute-E [J] channel (law §6/§7) flows ColumnTask.enthalpy →
     *  Flat.eIn → slice(eOut) → ColumnResult.enthalpy bit-identically, positionally, no rescale/cp·T. */
    @Test
    void enthalpyChannelRoundTripsFlattenSlice() {
        int N = RegionMarshaller.CHUNK_N;
        // Two columns with DISTINCT per-cell enthalpy: col0 cell k = 1000+k, col1 cell k = 5000+k.
        float[] e0 = new float[N], e1 = new float[N];
        for (int k = 0; k < N; k++) { e0[k] = 1000f + k; e1[k] = 5000f + k; }
        ColumnTask a = new ColumnTask(2, -1, new char[N], new float[N], new float[N],
                new float[N], new float[N], new float[N], new float[N], new float[N], e0);
        ColumnTask b = new ColumnTask(7, 4, new char[N], new float[N], new float[N],
                new float[N], new float[N], new float[N], new float[N], new float[N], e1);

        RegionMarshaller.Flat f = RegionMarshaller.flatten(List.of(a, b));
        // Flat.eIn is column-concatenated at base c*CHUNK_N + k.
        assertEquals(N * 2, f.eIn().length);
        for (int k = 0; k < N; k += 7919) {           // sample strides (prime) — exact at every offset
            assertEquals(1000f + k, f.eIn()[k],            "col0 E at base 0");
            assertEquals(5000f + k, f.eIn()[N + k],        "col1 E at base CHUNK_N");
        }
        assertEquals(1000f + (N - 1), f.eIn()[N - 1]);
        assertEquals(5000f + (N - 1), f.eIn()[2 * N - 1]);

        // Slice DISTINCT eOut back; assert each ColumnResult.enthalpy matches bit-identically.
        float[] eOut = new float[N * 2];
        for (int k = 0; k < N; k++) { eOut[k] = 9000f + k; eOut[N + k] = 13000f + k; }
        List<ColumnResult> r = RegionMarshaller.slice(f.matIx(), f.mass(), f.tIn(),
                f.vxIn(), f.vyIn(), f.vzIn(), f.pIn(), f.swapReadyIn(), eOut, 2);
        for (int k = 0; k < N; k += 7919) {
            assertEquals(9000f + k,  r.get(0).enthalpy()[k], "col0 enthalpy out");
            assertEquals(13000f + k, r.get(1).enthalpy()[k], "col1 enthalpy out");
        }
        assertEquals(9000f + (N - 1),  r.get(0).enthalpy()[N - 1]);
        assertEquals(13000f + (N - 1), r.get(1).enthalpy()[N - 1]);
    }

    private static int idx(int x, int y, int z) { return x + 16 * y + 6144 * z; }
}
