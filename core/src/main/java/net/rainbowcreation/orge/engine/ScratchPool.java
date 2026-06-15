package net.rainbowcreation.orge.engine;

/**
 * Per-worker reusable scratch arrays for the native step's flat outputs (DESIGN §10 Decision 13c).
 * The engine runs single-threaded on the runner's background thread, so one buffer set (no
 * {@link ThreadLocal}) suffices. Arrays grow to the batch high-water mark and are kept, so a steady 4 Hz
 * cadence allocates nothing after warm-up — the result slices ({@code RegionMarshaller.slice}) copy the
 * per-column data out before the next step reuses the scratch.
 *
 * <p>Not thread-safe by design: confined to the single engine worker thread.</p>
 */
public final class ScratchPool {

    private float[] temp = new float[0];
    private float[] mass = new float[0];
    private char[] material = new char[0];
    private float[] velXIn  = new float[0];
    private float[] velYIn  = new float[0];
    private float[] velZIn  = new float[0];
    private float[] velXOut = new float[0];
    private float[] velYOut = new float[0];
    private float[] velZOut = new float[0];
    private float[] pOut    = new float[0];
    // T10.8: absolute-E (law #6) + swap-cadence accumulator (law #7, §5.3) JNI channels.
    private float[] eIn          = new float[0];
    private float[] swapReadyIn  = new float[0];
    private float[] eOut         = new float[0];
    private float[] swapReadyOut = new float[0];

    /** A {@code float[]} of at least {@code n}, reused when the held array is already big enough. */
    public float[] temp(int n) {
        if (temp.length < n) temp = new float[n];
        return temp;
    }

    public float[] mass(int n) {
        if (mass.length < n) mass = new float[n];
        return mass;
    }

    public char[] material(int n) {
        if (material.length < n) material = new char[n];
        return material;
    }

    public float[] velXIn(int n) {
        if (velXIn.length < n) velXIn = new float[n];
        return velXIn;
    }

    public float[] velYIn(int n) {
        if (velYIn.length < n) velYIn = new float[n];
        return velYIn;
    }

    public float[] velZIn(int n) {
        if (velZIn.length < n) velZIn = new float[n];
        return velZIn;
    }

    public float[] velXOut(int n) {
        if (velXOut.length < n) velXOut = new float[n];
        return velXOut;
    }

    public float[] velYOut(int n) {
        if (velYOut.length < n) velYOut = new float[n];
        return velYOut;
    }

    public float[] velZOut(int n) {
        if (velZOut.length < n) velZOut = new float[n];
        return velZOut;
    }

    /** Dynamic-pressure output channel ({@code p}). Mirrors {@link #velXOut(int)}. */
    public float[] pOut(int n) {
        if (pOut.length < n) pOut = new float[n];
        return pOut;
    }

    /**
     * Absolute-enthalpy input channel ({@code Ein} [J], law #6). The caller MUST fill every
     * slot it intends to use (the held array may carry stale data past {@code n}); T10.8 rebuilds
     * Ein = mass·cp·T over exactly the {@code total} cells each step, so there is no stale read.
     */
    public float[] eIn(int n) {
        if (eIn.length < n) eIn = new float[n];
        return eIn;
    }

    /**
     * Swap-cadence accumulator input channel ({@code swapReadyIn}, law #7 / §5.3). As of T10c,
     * {@link NativeEngine} no longer sources its swapReadyIn from this buffer — it takes the marshalled
     * {@code Flat.swapReadyIn} (the PERSISTED {@link ColumnTask#swapReady} channel) straight through, so
     * the §5.3 seconds-floor cadence accumulates across stepWorld calls. This buffer is kept (harmless)
     * but may be unreferenced; do not assert any zero-seed-each-call behavior on it.
     */
    public float[] swapReadyIn(int n) {
        if (swapReadyIn.length < n) swapReadyIn = new float[n];
        return swapReadyIn;
    }

    /** Absolute-enthalpy output channel ({@code Eout} [J]). Captured from the native; see T10.8 note. */
    public float[] eOut(int n) {
        if (eOut.length < n) eOut = new float[n];
        return eOut;
    }

    /** Swap-cadence accumulator output channel ({@code swapReadyOut}). Captured from the native. */
    public float[] swapReadyOut(int n) {
        if (swapReadyOut.length < n) swapReadyOut = new float[n];
        return swapReadyOut;
    }
}
