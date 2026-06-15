package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.rainbowcreation.orge.engine.RegionMarshaller.CHUNK_N;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 / S1 RED — the EXTENSIVE-momentum marshalling round-trip (mirrors the enthalpy/velocity round-trip in
 * {@code VelocityMarshallingTest}). After S2/S3, {@link ColumnTask}/{@link ColumnResult} carry
 * {@code momX/momY/momZ} (extensive momentum [kg·m/s], replacing velX/velY/velZ) and
 * {@link RegionMarshaller.Flat} carries {@code pxIn/pyIn/pzIn} (replacing vxIn/vyIn/vzIn);
 * {@code flatten} copies {@code momX → pxIn} and {@code slice} copies {@code pxOut → ColumnResult.momX}.
 *
 * <p>This file is authored against that POST-FIX API and will NOT COMPILE until S2/S3 rename the channels —
 * the intended TDD RED. The GREEN gate runs after S3/S4.</p>
 */
class MomentumMarshallingTest {

    @Test
    void flattenSliceRoundTripsMomentum() {
        char[] mat = new char[CHUNK_N];
        float[] mass = new float[CHUNK_N];
        float[] t = new float[CHUNK_N];
        float[] px = new float[CHUNK_N];
        float[] py = new float[CHUNK_N];
        float[] pz = new float[CHUNK_N];

        // Probe a spread of indices: prime-stride interior cells + both endpoints (0 and N-1).
        int[] probes = {0, 7919, 31337, 65521, CHUNK_N - 1};
        for (int i = 0; i < probes.length; i++) {
            int p = probes[i];
            px[p] = 100f + i;
            py[p] = -200f - i;
            pz[p] = 0.5f * (i + 1);
        }

        // POST-FIX 8-arg canonical ColumnTask: momX/momY/momZ in the slots velX/velY/velZ used to hold.
        ColumnTask col = new ColumnTask(3, -7, mat, mass, t, px, py, pz);
        RegionMarshaller.Flat flat = RegionMarshaller.flatten(List.of(col));

        // flatten copies momX → pxIn (etc.), bit-identical.
        for (int p : probes) {
            assertEquals(px[p], flat.pxIn()[p], 0f, "momX flattened into pxIn @ " + p);
            assertEquals(py[p], flat.pyIn()[p], 0f, "momY flattened into pyIn @ " + p);
            assertEquals(pz[p], flat.pzIn()[p], 0f, "momZ flattened into pzIn @ " + p);
        }

        // Round-trip the same flat momentum back out through slice (echo): pxOut = pxIn.
        List<ColumnResult> out = RegionMarshaller.slice(
                flat.matIx(), flat.mass(), flat.tIn(),
                flat.pxIn(), flat.pyIn(), flat.pzIn(), 1);
        ColumnResult r = out.get(0);
        for (int p : probes) {
            assertEquals(px[p], r.momX()[p], 0f, "pxOut sliced back into ColumnResult.momX @ " + p);
            assertEquals(py[p], r.momY()[p], 0f, "pyOut sliced back into ColumnResult.momY @ " + p);
            assertEquals(pz[p], r.momZ()[p], 0f, "pzOut sliced back into ColumnResult.momZ @ " + p);
        }
    }

    @Test
    void backCompatConstructorsZeroFillMomentum() {
        char[] mat = new char[CHUNK_N];
        float[] mass = new float[CHUNK_N];
        float[] t = new float[CHUNK_N];
        // OLD 5-arg ColumnTask still compiles; momentum defaults zero-filled to CHUNK_N.
        ColumnTask col = new ColumnTask(0, 0, mat, mass, t);
        assertEquals(CHUNK_N, col.momX().length);
        assertEquals(0f, col.momX()[100]);
        ColumnResult r = new ColumnResult(mat, mass, t);       // OLD 3-arg still compiles
        assertEquals(CHUNK_N, r.momY().length);
        assertEquals(0f, r.momZ()[100]);
    }
}
