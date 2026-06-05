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
}
