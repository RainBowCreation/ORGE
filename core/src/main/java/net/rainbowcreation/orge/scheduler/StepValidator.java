package net.rainbowcreation.orge.scheduler;

/**
 * Validates an engine result array before it is written to the {@link
 * net.rainbowcreation.orge.section.SectionStore} (DESIGN §9 trust model). Per cell:
 * a non-finite value (NaN / ±Inf) keeps the supplied {@code fallback} (the snapshot input
 * temperature), so corruption can't spread; a finite value is clamped to the engine's
 * {@code [0, 6000]} K range. The §9 "assigned?" check is moot single-node (no remote results).
 */
public final class StepValidator {

    public static final float MIN_K = 0f;
    public static final float MAX_K = 6000f;

    private StepValidator() {}

    /**
     * Returns a fresh sanitized copy of {@code result}: each non-finite cell takes the
     * corresponding {@code fallback} value (the snapshot input temperature), and each finite
     * cell is clamped to {@code [MIN_K, MAX_K]}. Inputs are not mutated.
     *
     * @param result   the engine's raw output temperatures
     * @param fallback the values to keep where {@code result} is non-finite; must be at least
     *                 as long as {@code result}
     * @return a new array of length {@code result.length}
     * @throws IllegalArgumentException if {@code fallback} is shorter than {@code result}
     */
    public static float[] clean(float[] result, float[] fallback) {
        if (fallback.length < result.length) {
            throw new IllegalArgumentException(
                    "fallback (" + fallback.length + ") shorter than result (" + result.length + ")");
        }
        float[] out = new float[result.length];
        for (int i = 0; i < result.length; i++) {
            float v = result[i];
            if (!Float.isFinite(v)) {
                out[i] = fallback[i];
            } else if (v < MIN_K) {
                out[i] = MIN_K;
            } else if (v > MAX_K) {
                out[i] = MAX_K;
            } else {
                out[i] = v;
            }
        }
        return out;
    }
}
