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

    /** The single grow rule (DESIGN §10 Decision 13c): return {@code buf} when it already holds at least
     *  {@code n} slots, else a fresh {@code float[n]}. Every per-channel accessor below is a one-line
     *  {@code field = grow(field, n)} over this helper, so the grow-to-high-water-mark policy lives in
     *  exactly one place. */
    private static float[] grow(float[] buf, int n) {
        return buf.length < n ? new float[n] : buf;
    }

    /** The {@code char[]} variant of {@link #grow(float[], int)} (the material channel). */
    private static char[] grow(char[] buf, int n) {
        return buf.length < n ? new char[n] : buf;
    }

    /** A {@code float[]} of at least {@code n}, reused when the held array is already big enough. */
    public float[] temp(int n) { return temp = grow(temp, n); }

    public float[] mass(int n) { return mass = grow(mass, n); }

    public char[] material(int n) { return material = grow(material, n); }

    public float[] velXIn(int n) { return velXIn = grow(velXIn, n); }

    public float[] velYIn(int n) { return velYIn = grow(velYIn, n); }

    public float[] velZIn(int n) { return velZIn = grow(velZIn, n); }

    public float[] velXOut(int n) { return velXOut = grow(velXOut, n); }

    public float[] velYOut(int n) { return velYOut = grow(velYOut, n); }

    public float[] velZOut(int n) { return velZOut = grow(velZOut, n); }

    /** Dynamic-pressure output channel ({@code p}). Mirrors {@link #velXOut(int)}. */
    public float[] pOut(int n) { return pOut = grow(pOut, n); }

    /**
     * Absolute-enthalpy input channel ({@code Ein} [J], law #6). The caller MUST fill every
     * slot it intends to use (the held array may carry stale data past {@code n}); T10.8 rebuilds
     * Ein = mass·cp·T over exactly the {@code total} cells each step, so there is no stale read.
     */
    public float[] eIn(int n) { return eIn = grow(eIn, n); }

    /**
     * Swap-cadence accumulator input channel ({@code swapReadyIn}, law #7 / §5.3). As of T10c,
     * {@link NativeEngine} no longer sources its swapReadyIn from this buffer — it takes the marshalled
     * {@code Flat.swapReadyIn} (the PERSISTED {@link ColumnTask#swapReady} channel) straight through, so
     * the §5.3 seconds-floor cadence accumulates across stepWorld calls. This buffer is kept (harmless)
     * but may be unreferenced; do not assert any zero-seed-each-call behavior on it.
     */
    public float[] swapReadyIn(int n) { return swapReadyIn = grow(swapReadyIn, n); }

    /** Absolute-enthalpy output channel ({@code Eout} [J]). Captured from the native; see T10.8 note. */
    public float[] eOut(int n) { return eOut = grow(eOut, n); }

    /** Swap-cadence accumulator output channel ({@code swapReadyOut}). Captured from the native. */
    public float[] swapReadyOut(int n) { return swapReadyOut = grow(swapReadyOut, n); }
}
