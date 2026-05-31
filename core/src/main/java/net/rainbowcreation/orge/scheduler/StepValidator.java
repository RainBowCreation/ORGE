package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;

import java.util.List;

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
        return massConserved(after, before, fullMassBound, null, null);
    }

    /**
     * Fluid-aware §9 mass gate: identical to {@link #massConserved(float[], float[], float)} but
     * <b>only fluid cells</b> participate. Conservation and the full-mass bound are
     * advection invariants — solids don't advect and their stored mass is just thermal mass, so a
     * solid carrying a stale value (e.g. a {@code lava→obsidian} cell that kept lava's 3100 kg while
     * the batch bound dropped to {@code generic_solid}'s 2500) must NOT fail the gate. {@link #cleanMass}
     * still clamps that solid to the bound on write-back. When {@code matIx}/{@code lut} are {@code null}
     * every cell counts (used by the pure conservation tests).
     *
     * @param matIx per-cell material indices into {@code lut}, or {@code null} to count every cell
     * @param lut   the batch material table (index 0 = {@link MaterialLut#VOID}), or {@code null}
     */
    public static boolean massConserved(float[] after, float[] before, float fullMassBound,
                                        char[] matIx, List<Material> lut) {
        double sumA = 0, sumB = 0;
        float cellEps = MASS_EPSILON_PER_CELL;
        for (int i = 0; i < after.length; i++) {
            if (lut != null && !lut.get(matIx[i]).fluid()) {
                continue; // solids/air: not an advection mass, exempt from conservation + bound
            }
            if (!Float.isFinite(after[i])) return false;
            if (after[i] < -cellEps || after[i] > fullMassBound + cellEps) return false;
            sumA += after[i]; sumB += before[i];
        }
        return Math.abs(sumA - sumB) <= cellEps * after.length;
    }

    /**
     * §9 per-species mass gate (Spec Decision 6/12, Phase-2b). One O(N) pass with a <b>dual-index</b>
     * conservation sum: each cell's {@code before} mass is accumulated under its <b>input</b> species
     * ({@code inMat[i]}) and its {@code after} mass under its <b>output</b> species ({@code outMat[i]}),
     * each only when that species is a tracked fluid (index != 0 and {@code fluid()}). After the pass,
     * every tracked fluid species must be conserved within {@code ε·N}. The conservation sum is
     * <b>never exempted</b> — this is what makes it the real gate: it conserves correctly across
     * wetting (donor water→water loses 125, recipient air→water gains 125, both under the water index →
     * water sumBefore 1000 == sumAfter 875+125), full-cell density swaps with air (steam below air: the
     * lower cell counts its input steam in sumBefore, the upper cell counts its output steam in sumAfter
     * → 0.6 == 0.6), liquid sort swaps (each species conserved on its own dual index), and drains (the
     * neighbours' after-sums under their own output species capture the donated mass), and it forbids
     * mass invention (a water→air drain mislabeled as lava fails because lava's after-sum would exceed
     * its before-sum while water's after-sum falls short).
     *
     * <p>Separately, each fluid cell is bounded on its <b>output</b> species,
     * {@code after[i] ∈ [−ε, maxMass(outMat[i]) + ε]}. The ONLY exemption is from this BOUND: a cell
     * already <b>over its own output-species cap</b> ({@code after[i] > maxMass(outMat[i])}) is a
     * transient compressed parcel — a §7/engine boil deposit (Decision 12 boil-volume) that the
     * advection pass relieves over the next steps — and skips the bound. The exemption key is
     * "over cap", NOT "species changed this step": a boiled steam cell stays steam for the multiple
     * steps it takes to relax, so a species-change key would stop exempting it after step 1 and freeze
     * the still-relaxing over-cap steam. Exempting an over-cap cell from the bound is safe precisely
     * because the dual-index conservation sum (never exempted) still prevents mass invention. Air/void
     * (index 0) and solids are not advection masses and contribute to neither sum.
     *
     * @param after   engine mass output (length N)
     * @param before  snapshot input mass (length N)
     * @param inMat   per-cell INPUT species (the snapshot {@code matIx}); index into {@code lut}
     * @param outMat  per-cell OUTPUT species (the engine's {@code material()}); index into {@code lut}
     * @param lut     batch material table (index 0 = {@link MaterialLut#VOID})
     */
    public static boolean massConservedPerSpecies(float[] after, float[] before,
                                                  char[] inMat, char[] outMat,
                                                  List<Material> lut) {
        float cellEps = MASS_EPSILON_PER_CELL;
        int speciesCount = lut.size();
        double[] sumBefore = new double[speciesCount];
        double[] sumAfter = new double[speciesCount];
        for (int i = 0; i < after.length; i++) {
            if (!Float.isFinite(after[i])) return false;
            int in = inMat[i];
            int out = outMat[i];
            // BEFORE conserved under the cell's INPUT species; AFTER under its OUTPUT species. The two
            // sums are decoupled, so a wetted air cell (air in, water out) does not count its 0 'before'
            // under water yet contributes its 'after' to water -> donor + recipient balance under water.
            if (in != 0 && lut.get(in).fluid()) {
                sumBefore[in] += before[i];
            }
            if (out != 0 && lut.get(out).fluid()) {
                float bound = lut.get(out).maxMass(); // per-species cap (canonical accessor: 0 -> defaultMass)
                // BOUND on the output species, exempting a cell already OVER its own cap (a transient
                // §7/engine boil deposit relaxing over the next steps). The conservation sum below is
                // NEVER exempted, so the exemption can hide an over-cap parcel but never invented mass.
                if (!(after[i] > bound) && (after[i] < -cellEps || after[i] > bound + cellEps)) {
                    return false;
                }
                sumAfter[out] += after[i];
            }
        }
        double tol = (double) cellEps * after.length;
        for (int s = 1; s < speciesCount; s++) {
            if (Math.abs(sumAfter[s] - sumBefore[s]) > tol) return false;
        }
        return true;
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
