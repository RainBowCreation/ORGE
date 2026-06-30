package net.rainbowcreation.orge.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * The deep module behind one whole-region engine step: it owns the entire marshalling assembly that
 * {@link NativeEngine#stepWorld} used to hand-write inline — flatten the {@link ColumnTask}s into the
 * flat JNI arrays ({@link RegionMarshaller#flatten}), reuse grow-and-keep scratch buffers
 * ({@link ScratchPool}) for every OUT channel, pack the injection channel (empty when {@code injCount==0}),
 * invoke the raw multi-arg native call through a {@link Kernel}, copy the per-species ledger back out, and
 * slice the flat outputs into {@link ColumnResult}s ({@link RegionMarshaller#slice}).
 *
 * <p>The only thing left OUTSIDE this class is the {@link Kernel} — the raw {@code orgeStepWorld} JNI
 * signature. NativeEngine supplies the real native binding as a method reference; tests supply a fake
 * Kernel to exercise flatten/scratch-reuse/ledger-copyback/slice without the native library. The JNI
 * argument ORDER and COUNT live in exactly one place: {@link Kernel#step}.</p>
 *
 * <p>Stateful only in the sense that it holds the per-worker {@link ScratchPool} (grow-and-keep reuse
 * across same-size steps) and the last native compute time. Confined to the single engine worker thread,
 * exactly like {@link ScratchPool}.</p>
 */
public final class EngineMarshaller {

    /**
     * The raw native step, abstracted to a single-method seam so the marshalling around it is testable.
     * The argument ORDER and COUNT here are the ABI contract with {@code orge_jni.cpp}'s
     * {@code orgeStepWorld} — see {@link NativeEngine} for the per-channel ABI documentation. Returns the
     * native compute time in milliseconds.
     */
    @FunctionalInterface
    public interface Kernel {
        double step(
                int lutEpoch,
                int nCols, int[] cx, int[] cz,
                char[] matIx, float[] mass, float[] tIn,
                float[] vxIn, float[] vyIn, float[] vzIn, float[] pIn,
                int passes, double dtSeconds,
                float[] tOut, float[] massOut, char[] matOut,
                float[] vxOut, float[] vyOut, float[] vzOut, float[] pOut,
                int injCount,
                int[] injColumn, int[] injCell,
                char[] injSpecies, float[] injMass, float[] injTemp,
                float[] ledgerOut,
                float[] eIn, float[] swapReadyIn,
                float[] eOut, float[] swapReadyOut);
    }

    // Empty injection channel until Plan 2 wires the placement queue. With injCount==0 the native skips
    // the whole injection block and never reads/writes these, so shared immutable empties (incl.
    // ledgerOut) avoid per-step hot-path garbage.
    private static final int[] EMPTY_INT = new int[0];
    private static final char[] EMPTY_CHAR = new char[0];
    private static final float[] EMPTY_FLOAT = new float[0];

    private final ScratchPool scratch = new ScratchPool();
    private double lastStepMillis = 0.0;

    /**
     * Marshal {@code columns} (+ optional {@code injections}) through {@code kernel} and slice the result.
     * {@code matCount} sizes the per-species ledger. Behaviour-identical to the old inline assembly in
     * {@link NativeEngine#stepWorld}; the empty-column fast path (zero millis, empty ledgers) is handled
     * here too so the Kernel is never called with no work.
     */
    public RegionStepResult step(List<ColumnTask> columns, int lutEpoch, int matCount,
                                 double dtSeconds, int passes,
                                 List<EngineInjection> injections, Kernel kernel) {
        if (columns.isEmpty()) {
            lastStepMillis = 0.0;
            return new RegionStepResult(new ArrayList<>(),
                    new float[matCount], new float[matCount],
                    new float[matCount], new float[matCount]);
        }
        RegionMarshaller.Flat f = RegionMarshaller.flatten(columns);
        int total = f.nCols() * RegionMarshaller.CHUNK_N;
        float[] tOut = scratch.temp(total);
        float[] massOut = scratch.mass(total);
        char[] matOut = scratch.material(total);

        int injCount = injections.size();
        int[] injCol; int[] injCell; char[] injSp; float[] injMs; float[] injTp; float[] ledgerOut;
        if (injCount == 0) {
            injCol = EMPTY_INT; injCell = EMPTY_INT; injSp = EMPTY_CHAR;
            injMs = EMPTY_FLOAT; injTp = EMPTY_FLOAT; ledgerOut = EMPTY_FLOAT;
        } else {
            injCol = new int[injCount]; injCell = new int[injCount]; injSp = new char[injCount];
            injMs = new float[injCount]; injTp = new float[injCount];
            for (int k = 0; k < injCount; k++) {
                EngineInjection in = injections.get(k);
                injCol[k] = in.columnId(); injCell[k] = in.cellIndex();
                injSp[k] = in.species(); injMs[k] = in.mass(); injTp[k] = in.temperature();
            }
            // T10.8: ledger is now [4*matCount] = injected, sealedLoss, injectedE, sealedE. The C++
            // writes the E side (indices [2n..4n)) ONLY because we now pass it room (length >= 4*matCount).
            ledgerOut = new float[4 * matCount];
        }

        // F2 momentum ABI (law §7): the momentum slots carry EXTENSIVE momentum p [kg·m/s] directly,
        // sourced straight from Flat.pxIn()/pyIn()/pzIn() (= ColumnTask.momX/momY/momZ). No v→p
        // reconstruction here and none in the JNI seam — the engine loads p absolutely (mirror of the
        // absolute-E channel below). The local var names stay vxIn/vyIn/vzIn only to match the ABI-stable
        // positional slots of the Kernel; their CONTENT is momentum, not velocity.
        float[] vxIn  = f.pxIn();
        float[] vyIn  = f.pyIn();
        float[] vzIn  = f.pzIn();
        float[] pIn   = f.pIn();
        // OUT slots also carry EXTENSIVE momentum p [kg·m/s] (the JNI writes C->px/py/pz directly, no p/m).
        // These thread into RegionMarshaller.slice's momentum positions → ColumnResult.momX/momY/momZ.
        float[] vxOut = scratch.velXOut(total);
        float[] vyOut = scratch.velYOut(total);
        float[] vzOut = scratch.velZOut(total);
        float[] pOut  = scratch.pOut(total);

        // T2 S5 absolute-E feed (law §6 — E is THE energy truth carrier, T is a derived diagnostic).
        // eIn flows straight from the S4-threaded stored-E channel (ColumnTask.enthalpy →
        // RegionMarshaller.Flat.eIn), exactly like vxIn = f.pxIn() and swapReadyIn = f.swapReadyIn():
        // the engine receives the STORED ABSOLUTE E [J] unmodified — loss-free across the JNI seam.
        float[] eIn = f.eIn();
        // swapReadyIn: sourced from the persisted Java channel (ColumnTask.swapReady, marshalled into
        // Flat.swapReadyIn) — taken straight from Flat, exactly like vxIn = f.pxIn(). The engine reads it
        // directly, round-trips it, and resets-on-mismatch per v4 §1.1. Java now PERSISTS this channel
        // across stepWorld calls (T10c) so the §5.3 seconds-floor swap cadence can accumulate toward its
        // ≥1 fire threshold instead of being re-zeroed each tick.
        float[] swapReadyIn = f.swapReadyIn();
        // Eout: the engine's AUTHORITATIVE post-step absolute E [J] (law §6). swapReadyOut: persisted
        // §5.3 accumulator. Both captured from the native into reusable scratch.
        float[] eOut = scratch.eOut(total);
        float[] swapReadyOut = scratch.swapReadyOut(total);

        lastStepMillis = kernel.step(
                lutEpoch,
                f.nCols(), f.cx(), f.cz(), f.matIx(), f.mass(), f.tIn(),
                vxIn, vyIn, vzIn, pIn,
                passes, dtSeconds, tOut, massOut, matOut,
                vxOut, vyOut, vzOut, pOut,
                injCount, injCol, injCell, injSp, injMs, injTp, ledgerOut,
                eIn, swapReadyIn, eOut, swapReadyOut);

        float[] injected = new float[matCount];
        float[] sealedLoss = new float[matCount];
        float[] injectedE = new float[matCount];
        float[] sealedE = new float[matCount];
        if (injCount > 0 && matCount > 0) {
            System.arraycopy(ledgerOut, 0, injected, 0, matCount);
            System.arraycopy(ledgerOut, matCount, sealedLoss, 0, matCount);
            // E side: indices [2n..4n) — injectedE, sealedE (the C++ wrote these because we sized the
            // ledger to 4*matCount above; law #9 boundary energy ledger).
            System.arraycopy(ledgerOut, 2 * matCount, injectedE, 0, matCount);
            System.arraycopy(ledgerOut, 3 * matCount, sealedE, 0, matCount);
        }
        return new RegionStepResult(
                RegionMarshaller.slice(matOut, massOut, tOut, vxOut, vyOut, vzOut, pOut, swapReadyOut, eOut, f.nCols()),
                injected, sealedLoss, injectedE, sealedE);
    }

    /** The native compute time in milliseconds of the last {@link #step}. */
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
