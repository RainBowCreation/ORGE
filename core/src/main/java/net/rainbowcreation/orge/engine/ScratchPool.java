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
}
