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

    /** Per-region mass-conservation tolerance: ε·N (ε = 1e-2 kg per cell). */
    public static final float MASS_EPSILON_PER_CELL = 1e-2f;

    /**
     * §9 invariant (DESIGN §10): true iff total mass is conserved within {@code ε·N}
     * (boundary in/out = 0, no-flow walls) AND every cell is within {@code [0, fullMass+ε]}.
     * Used as the accept/reject gate on a step's mass output.
     *
     * @param after        engine mass output (length N)
     * @param before       snapshot input mass (length N)
     * @param fullMassBound the largest legal per-cell mass for this section (max material defaultMass)
     */
    public static boolean massConserved(float[] after, float[] before, float fullMassBound) {
        double sumA = 0, sumB = 0;
        float cellEps = MASS_EPSILON_PER_CELL;
        for (int i = 0; i < after.length; i++) {
            if (!Float.isFinite(after[i])) return false;
            if (after[i] < -cellEps || after[i] > fullMassBound + cellEps) return false;
            sumA += after[i]; sumB += before[i];
        }
        return Math.abs(sumA - sumB) <= cellEps * after.length;
    }

    /** Non-finite mass → 0; finite mass clamped to [0, fullMassBound]. Mirrors {@link #clean}. */
    public static float[] cleanMass(float[] mass, float fullMassBound) {
        float[] out = new float[mass.length];
        for (int i = 0; i < mass.length; i++) {
            float v = mass[i];
            if (!Float.isFinite(v) || v < 0f) out[i] = 0f;
            else out[i] = Math.min(v, fullMassBound);
        }
        return out;
    }
}
