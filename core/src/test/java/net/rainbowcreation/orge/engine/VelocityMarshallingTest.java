package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static net.rainbowcreation.orge.engine.RegionMarshaller.CHUNK_N;

// F2-S5 (re-authored from the stale velocity-channel round-trip): per spec §1.3 the transported/stored
// "vx/vy/vz" symbol is RETIRED — the conserved carrier across the flatten/slice seam is EXTENSIVE momentum
// p [kg·m/s] (law §7 / §1.1). The channel ColumnTask formerly called velX/velY/velZ now holds momentum and
// is named momX/momY/momZ; RegionMarshaller.Flat carries pxIn/pyIn/pzIn. The momentum round-trip's full
// coverage (prime-stride probes + both endpoints + back-compat zero-fill) lives in MomentumMarshallingTest;
// these two re-authored cases assert the SAME §1.3 fact at the marshalling slot (momentum, never velocity)
// without re-using the retired symbol, and the file's PRESSURE round-trip below is left intact (pressure IS
// a legitimately-transported channel, not a retired symbol).
class VelocityMarshallingTest {
    @Test
    void flattenSliceRoundTripsMomentumNotVelocity() {
        char[] mat = new char[CHUNK_N]; float[] mass = new float[CHUNK_N];
        float[] t = new float[CHUNK_N];
        // EXTENSIVE momentum p [kg·m/s] (§1.1/§7) seeded in the velX-slot the canonical ctor occupies; §1.3.
        float[] px = new float[CHUNK_N]; float[] py = new float[CHUNK_N]; float[] pz = new float[CHUNK_N];
        int probe = 12345;
        px[probe] = 1.5f; py[probe] = -2.0f; pz[probe] = 0.25f;
        ColumnTask col = new ColumnTask(3, -7, mat, mass, t, px, py, pz);  // 8-arg canonical: momX/momY/momZ
        RegionMarshaller.Flat flat = RegionMarshaller.flatten(List.of(col));
        assertEquals(1.5f, flat.pxIn()[probe], "momX flattened into the pxIn momentum channel (not velocity)");
        List<ColumnResult> out = RegionMarshaller.slice(
                flat.matIx(), flat.mass(), flat.tIn(), flat.pxIn(), flat.pyIn(), flat.pzIn(), 1);
        assertEquals(-2.0f, out.get(0).momY()[probe], "pyOut sliced back into ColumnResult.momY (momentum)");
    }

    @Test
    void backCompatConstructorsZeroFillMomentum() {
        char[] mat = new char[CHUNK_N]; float[] mass = new float[CHUNK_N]; float[] t = new float[CHUNK_N];
        ColumnTask col = new ColumnTask(0, 0, mat, mass, t);   // OLD 5-arg still compiles
        // The retired velocity channel is GONE; the back-compat ctor zero-fills the momentum channel (§1.3).
        assertEquals(CHUNK_N, col.momX().length);
        assertEquals(0f, col.momX()[100]);
        ColumnResult r = new ColumnResult(mat, mass, t);       // OLD 3-arg still compiles
        assertEquals(CHUNK_N, r.momY().length);
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
                flat.pxIn(), flat.pyIn(), flat.pzIn(), flat.pIn(), 1);
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
