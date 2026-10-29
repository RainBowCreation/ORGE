package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seam test for {@link EngineMarshaller}: exercises the full flatten → scratch-reuse → Kernel →
 * ledger-copyback → slice assembly through a FAKE {@link EngineMarshaller.Kernel}, with no native library.
 * This is the unit test the inline marshalling in NativeEngine never had — the native pipeline ITs only
 * covered it end-to-end.
 */
class EngineMarshallerTest {

    private static final int N = RegionMarshaller.CHUNK_N;

    private static int idx(int x, int y, int z) { return x + 16 * y + 6144 * z; }

    /** A Kernel that records every argument it receives and writes deterministic outputs back. */
    private static final class RecordingKernel implements EngineMarshaller.Kernel {
        int lutEpoch, nCols, passes, injCount, calls;
        double dtSeconds;
        /** Non-zero ⇒ eOut = eIn + offset (a kernel that adds a sub-float-ULP heat quantum to every cell). */
        double eInEchoOffset;
        int[] cx, cz, injColumn, injCell;
        char[] matIx, matOut, injSpecies;
        float[] mass, tIn, vxIn, vyIn, vzIn, pIn, swapReadyIn, injMass, injTemp;
        float[] tOut, massOut, vxOut, vyOut, vzOut, pOut, swapReadyOut, ledgerOut;
        double[] eIn, eOut, booksOut;
        char[] slotIxIn, slotIxOut;
        float[] slotMassIn, slotMassOut;
        float[] restIn, restOut;
        int restSteps;

        @Override
        public double step(int lutEpoch, int nCols, int[] cx, int[] cz,
                           char[] matIx, float[] mass, float[] tIn,
                           float[] vxIn, float[] vyIn, float[] vzIn, float[] pIn,
                           int passes, double dtSeconds,
                           float[] tOut, float[] massOut, char[] matOut,
                           float[] vxOut, float[] vyOut, float[] vzOut, float[] pOut,
                           int injCount, int[] injColumn, int[] injCell,
                           char[] injSpecies, float[] injMass, float[] injTemp,
                           float[] ledgerOut,
                           double[] eIn, float[] swapReadyIn, double[] eOut, float[] swapReadyOut,
                           char[] slotIxIn, float[] slotMassIn, char[] slotIxOut, float[] slotMassOut,
                           double[] booksOut,
                           float[] restIn, float[] restOut, int restSteps) {
            this.calls++;
            this.restIn = restIn; this.restOut = restOut; this.restSteps = restSteps;
            for (int i = 0; i < restOut.length; i++) restOut[i] = 0.75f + i;
            this.booksOut = booksOut;
            for (int i = 0; i < booksOut.length; i++) booksOut[i] = 0.125 + i;
            this.slotIxIn = slotIxIn; this.slotMassIn = slotMassIn;
            this.slotIxOut = slotIxOut; this.slotMassOut = slotMassOut;
            for (int i = 0; i < slotIxOut.length; i++) { slotIxOut[i] = (char) ((i + 3) & 0xFFFF); slotMassOut[i] = 0.25f + i; }
            this.lutEpoch = lutEpoch; this.nCols = nCols; this.passes = passes; this.dtSeconds = dtSeconds;
            this.cx = cx; this.cz = cz; this.matIx = matIx; this.mass = mass; this.tIn = tIn;
            this.vxIn = vxIn; this.vyIn = vyIn; this.vzIn = vzIn; this.pIn = pIn;
            this.eIn = eIn; this.swapReadyIn = swapReadyIn;
            this.injCount = injCount; this.injColumn = injColumn; this.injCell = injCell;
            this.injSpecies = injSpecies; this.injMass = injMass; this.injTemp = injTemp;
            this.tOut = tOut; this.massOut = massOut; this.matOut = matOut;
            this.vxOut = vxOut; this.vyOut = vyOut; this.vzOut = vzOut; this.pOut = pOut;
            this.eOut = eOut; this.swapReadyOut = swapReadyOut; this.ledgerOut = ledgerOut;
            // Deterministic OUT fill: per-cell value derived from the global flat offset so slice() can be
            // verified to route each column to the right base.
            for (int i = 0; i < tOut.length; i++) {
                tOut[i] = 300f + i; massOut[i] = 1000f + i; matOut[i] = (char) (i & 0xFFFF);
                vxOut[i] = 10f + i; vyOut[i] = 20f + i; vzOut[i] = 30f + i; pOut[i] = 40f + i;
                eOut[i] = 5000f + i; swapReadyOut[i] = 0.5f + i;
            }
            // Deterministic ledger fill (only meaningful when length >= 4*matCount): index value = its slot.
            for (int i = 0; i < ledgerOut.length; i++) ledgerOut[i] = 100f + i;
            if (eInEchoOffset != 0.0) for (int i = 0; i < eOut.length; i++) eOut[i] = eIn[i] + eInEchoOffset;
            return 7.5;
        }
    }

    private static ColumnTask column(int cx, int cz, double eSeed) {
        double[] e = new double[N];
        for (int k = 0; k < N; k++) e[k] = eSeed + k;
        return new ColumnTask(cx, cz, new char[N], new float[N], new float[N],
                new float[N], new float[N], new float[N], new float[N], new float[N], e);
    }

    @Test
    void flattenFeedsKernel_andSliceRoundTripsOutputs() {
        EngineMarshaller m = new EngineMarshaller();
        RecordingKernel k = new RecordingKernel();
        ColumnTask a = column(2, -1, 1000f);
        ColumnTask b = column(7, 4, 5000f);

        RegionStepResult r = m.step(List.of(a, b), 42, 0, 0.05, OrgeEngine.PASS_CONDUCTION,
                List.of(), 20, k);

        // flatten correctness: scalars + per-column coords + the absolute-E channel reached the Kernel.
        assertEquals(1, k.calls);
        assertEquals(42, k.lutEpoch);
        assertEquals(2, k.nCols);
        assertEquals(OrgeEngine.PASS_CONDUCTION, k.passes);
        assertEquals(0.05, k.dtSeconds);
        assertArrayEquals(new int[]{2, 7}, k.cx);
        assertArrayEquals(new int[]{-1, 4}, k.cz);
        assertEquals(2 * N, k.eIn.length);
        assertEquals(1000f, k.eIn[0]);
        assertEquals(5000f, k.eIn[N]);
        assertEquals(1000f + (N - 1), k.eIn[N - 1]);

        // slice round-trip: each ColumnResult cell carries the Kernel's OUT value at its global base.
        assertEquals(2, r.columns().size());
        assertEquals(7.5, m.lastStepMillis());
        ColumnResult c0 = r.columns().get(0), c1 = r.columns().get(1);
        int probe = idx(3, 134, 5);
        assertEquals(300f + probe, c0.temperature()[probe]);
        assertEquals(1000f + probe, c0.mass()[probe]);
        assertEquals(5000f + probe, c0.enthalpy()[probe]);
        // column 1 lives at global base N, so its cell `probe` came from flat offset N+probe.
        assertEquals(300f + N + probe, c1.temperature()[probe]);
        assertEquals(5000f + N + probe, c1.enthalpy()[probe]);
        assertEquals(40f + N + probe, c1.p()[probe]);
        // rest freeze: the threshold reaches the Kernel and the counter round-trips in and out.
        assertEquals(20, k.restSteps);
        assertEquals(2 * N, k.restIn.length);
        assertEquals(0.75f + probe, c0.restCount()[probe]);
        assertEquals(0.75f + N + probe, c1.restCount()[probe]);
    }

    /** Issue #44: E crosses the marshaller at double both ways — a 0.5 J quantum on a 6e8 J cell (64 J
     *  float32 ULP) survives flatten → Kernel → slice — and the Kernel's books reach the result. */
    @Test
    void absoluteECrossesAtDouble_andBooksReachResult() {
        EngineMarshaller m = new EngineMarshaller();
        RecordingKernel k = new RecordingKernel();
        k.eInEchoOffset = 0.5;
        final double bigE = 6.0e8 + 0.25;   // not float32-representable
        final int matCount = 4;

        RegionStepResult r = m.step(List.of(column(0, 0, bigE)), 3, matCount, 0.05, 0, List.of(), 0, k);

        int probe = idx(5, 77, 11);
        assertEquals(bigE + probe, k.eIn[probe], 0.0, "stored E reaches the Kernel bit-exact (double eIn)");
        assertEquals(bigE + probe + 0.5, r.columns().get(0).enthalpy()[probe], 0.0,
                "a sub-ULP heat quantum survives the slice back (double eOut)");
        assertEquals(RegionStepResult.BOOKS_HEAD + matCount, k.booksOut.length,
                "books sized for the fixed head + one ε-mass slot per species");
        assertEquals(0.125, r.boundaryE(), 0.0, "boundaryE copied out of the Kernel books");
        assertEquals(1.125 + 2.125, r.dissipationE(), 0.0, "damp + swap-PE heat copied out");
        assertEquals(3.125 + 2, r.boundaryMassEps(2), 0.0, "per-species ε-mass sink copied out");
        assertEquals(0.0, r.boundaryMassEps(matCount), 0.0, "past the booked species range reads 0");
    }

    /** DESIGN-LAW v5 #7 (issue #26): the composition slots 1..K−1 cross the seam in both directions,
     *  each column's SLOT_N block at c·SLOT_N (slot k at (k−1)·CHUNK_N inside it). */
    @Test
    void compositionSlots_flattenAndSliceRoundTrip() {
        final int S = RegionMarshaller.SLOT_N;
        char[] six = new char[S]; float[] sm = new float[S];
        int cell = idx(4, 200, 9);
        six[cell] = 3; sm[cell] = 0.6f;                         // slot 1: 0.6 kg of species 3
        six[2 * N + cell] = 5; sm[2 * N + cell] = 0.01f;        // slot 3: 0.01 kg of species 5
        ColumnTask a = column(0, 0, 0f);
        ColumnTask b = new ColumnTask(1, 0, new char[N], new float[N], new float[N],
                new float[N], new float[N], new float[N], new float[N], new float[N], new double[N], six, sm);
        EngineMarshaller m = new EngineMarshaller();
        RecordingKernel k = new RecordingKernel();
        RegionStepResult r = m.step(List.of(a, b), 1, 0, 0.05, OrgeEngine.PASS_ADVECTION, List.of(), 0, k);

        assertEquals(2 * S, k.slotIxIn.length);
        assertEquals(2 * S, k.slotMassIn.length);
        assertEquals(0f, k.slotMassIn[cell], "column 0 carries no second component");
        assertEquals(3, k.slotIxIn[S + cell]);
        assertEquals(0.6f, k.slotMassIn[S + cell]);
        assertEquals(5, k.slotIxIn[S + 2 * N + cell]);
        assertEquals(0.01f, k.slotMassIn[S + 2 * N + cell]);

        ColumnResult c1 = r.columns().get(1);
        assertEquals(S, c1.slotIx().length);
        assertEquals((char) ((S + cell + 3) & 0xFFFF), c1.slotIx()[cell]);
        assertEquals(0.25f + S + cell, c1.slotMass()[cell]);
    }

    @Test
    void emptyInjections_passEmptyChannelsAndNoLedgerCopyback() {
        EngineMarshaller m = new EngineMarshaller();
        RecordingKernel k = new RecordingKernel();

        RegionStepResult r = m.step(List.of(column(0, 0, 0f)), 1, 3, 0.05, 0, List.of(), 0, k);

        assertEquals(0, k.injCount);
        assertEquals(0, k.injColumn.length);
        assertEquals(0, k.injCell.length);
        assertEquals(0, k.injSpecies.length);
        assertEquals(0, k.ledgerOut.length, "zero injections passes the shared empty ledger");
        // matCount=3 but no injection: ledgers are zero-length copyback targets sized to matCount.
        assertArrayEquals(new float[3], r.injected());
        assertArrayEquals(new float[3], r.sealedLoss());
        assertArrayEquals(new float[3], r.injectedE());
        assertArrayEquals(new float[3], r.sealedE());
    }

    @Test
    void ledgerCopyback_splitsFourSpeciesBands() {
        EngineMarshaller m = new EngineMarshaller();
        RecordingKernel k = new RecordingKernel();
        int matCount = 2;
        EngineInjection inj = new EngineInjection(0, idx(1, 64, 1), (char) 1, 1000f, 350f);

        RegionStepResult r = m.step(List.of(column(0, 0, 0f)), 9, matCount, 0.05, 0,
                List.of(inj), 0, k);

        // injection marshalling reached the Kernel.
        assertEquals(1, k.injCount);
        assertArrayEquals(new int[]{0}, k.injColumn);
        assertArrayEquals(new int[]{idx(1, 64, 1)}, k.injCell);
        assertEquals((char) 1, k.injSpecies[0]);
        assertEquals(1000f, k.injMass[0]);
        assertEquals(350f, k.injTemp[0]);
        assertEquals(4 * matCount, k.ledgerOut.length, "ledger sized to 4*matCount so C++ writes the E side");

        // ledgerOut[i] = 100+i; copyback slices [0,n)=injected, [n,2n)=sealedLoss, [2n,3n)=injectedE,
        // [3n,4n)=sealedE.
        assertArrayEquals(new float[]{100f, 101f}, r.injected());
        assertArrayEquals(new float[]{102f, 103f}, r.sealedLoss());
        assertArrayEquals(new float[]{104f, 105f}, r.injectedE());
        assertArrayEquals(new float[]{106f, 107f}, r.sealedE());
    }

    @Test
    void scratchBuffersAreReusedAcrossSameSizeSteps() {
        EngineMarshaller m = new EngineMarshaller();
        RecordingKernel k = new RecordingKernel();
        List<ColumnTask> cols = List.of(column(0, 0, 0f), column(1, 0, 0f));

        m.step(cols, 0, 0, 0.05, 0, List.of(), 0, k);
        float[] tOut1 = k.tOut, massOut1 = k.massOut, swap1 = k.swapReadyOut;
        double[] eOut1 = k.eOut;
        char[] matOut1 = k.matOut;
        float[] vx1 = k.vxOut, p1 = k.pOut;

        m.step(cols, 0, 0, 0.05, 0, List.of(), 0, k);
        // grow-and-keep: identical batch size returns the SAME scratch arrays (no realloc).
        assertSame(tOut1, k.tOut, "temp OUT reused");
        assertSame(massOut1, k.massOut, "mass OUT reused");
        assertSame(matOut1, k.matOut, "material OUT reused");
        assertSame(eOut1, k.eOut, "enthalpy OUT reused");
        assertSame(swap1, k.swapReadyOut, "swapReady OUT reused");
        assertSame(vx1, k.vxOut, "velX OUT reused");
        assertSame(p1, k.pOut, "pressure OUT reused");
    }

    @Test
    void emptyColumns_shortCircuitWithoutCallingKernel() {
        EngineMarshaller m = new EngineMarshaller();
        RecordingKernel k = new RecordingKernel();

        RegionStepResult r = m.step(List.of(), 0, 3, 0.05, 0, List.of(), 0, k);

        assertEquals(0, k.calls, "no columns => Kernel never invoked");
        assertEquals(0.0, m.lastStepMillis());
        assertTrue(r.columns().isEmpty());
        assertArrayEquals(new float[3], r.injected());
        assertArrayEquals(new float[3], r.sealedE());
    }
}
