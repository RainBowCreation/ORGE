package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static net.rainbowcreation.orge.engine.RegionMarshaller.CHUNK_N;

class VelocityMarshallingTest {
    @Test
    void flattenSliceRoundTripsVelocity() {
        char[] mat = new char[CHUNK_N]; float[] mass = new float[CHUNK_N];
        float[] t = new float[CHUNK_N];
        float[] vx = new float[CHUNK_N]; float[] vy = new float[CHUNK_N]; float[] vz = new float[CHUNK_N];
        int probe = 12345;
        vx[probe] = 1.5f; vy[probe] = -2.0f; vz[probe] = 0.25f;
        ColumnTask col = new ColumnTask(3, -7, mat, mass, t, vx, vy, vz);  // 8-arg canonical
        RegionMarshaller.Flat flat = RegionMarshaller.flatten(List.of(col));
        assertEquals(1.5f, flat.vxIn()[probe]);
        List<ColumnResult> out = RegionMarshaller.slice(
                flat.matIx(), flat.mass(), flat.tIn(), flat.vxIn(), flat.vyIn(), flat.vzIn(), 1);
        assertEquals(-2.0f, out.get(0).velY()[probe]);
    }

    @Test
    void backCompatConstructorsZeroFillVelocity() {
        char[] mat = new char[CHUNK_N]; float[] mass = new float[CHUNK_N]; float[] t = new float[CHUNK_N];
        ColumnTask col = new ColumnTask(0, 0, mat, mass, t);   // OLD 5-arg still compiles
        assertEquals(CHUNK_N, col.velX().length);
        assertEquals(0f, col.velX()[100]);
        ColumnResult r = new ColumnResult(mat, mass, t);       // OLD 3-arg still compiles
        assertEquals(CHUNK_N, r.velY().length);
    }

    @Test
    void flattenSliceRoundTripsPressure() {
        char[] mat = new char[CHUNK_N]; float[] mass = new float[CHUNK_N];
        float[] t = new float[CHUNK_N];
        float[] vx = new float[CHUNK_N]; float[] vy = new float[CHUNK_N]; float[] vz = new float[CHUNK_N];
        float[] p = new float[CHUNK_N];
        int probe = 54321;
        p[probe] = 9876.5f;
        ColumnTask col = new ColumnTask(3, -7, mat, mass, t, vx, vy, vz, p);  // 9-arg canonical
        RegionMarshaller.Flat flat = RegionMarshaller.flatten(List.of(col));
        assertEquals(9876.5f, flat.pIn()[probe], "pressure flattened");
        List<ColumnResult> out = RegionMarshaller.slice(
                flat.matIx(), flat.mass(), flat.tIn(),
                flat.vxIn(), flat.vyIn(), flat.vzIn(), flat.pIn(), 1);
        assertEquals(9876.5f, out.get(0).p()[probe], "pressure sliced back");
    }

    @Test
    void backCompatConstructorsZeroFillPressure() {
        char[] mat = new char[CHUNK_N]; float[] mass = new float[CHUNK_N]; float[] t = new float[CHUNK_N];
        float[] z = new float[CHUNK_N];
        // OLD 8-arg velocity ctor still compiles; p defaults to zero-filled.
        ColumnTask col = new ColumnTask(0, 0, mat, mass, t, z, z, z);
        assertEquals(CHUNK_N, col.p().length);
        assertEquals(0f, col.p()[100]);
        ColumnResult r = new ColumnResult(mat, mass, t, z, z, z);  // OLD 6-arg still compiles
        assertEquals(CHUNK_N, r.p().length);
        assertEquals(0f, r.p()[100]);
    }
}
