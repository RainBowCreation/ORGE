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
     * §11: which materials are TRACKED, CONSERVED advection species in the §9 ledger. A liquid/gas
     * ({@link Material#fluid()}) advects; AIR ({@link Material#air()}) is now a real, finite, conserved
     * gas that liquid <b>displaces</b> rather than consumes. Both are summed and conserved per species.
     * VACUUM (index 0 / void) is excluded by the caller's {@code index != 0} guard — it is no species and
     * contributes 0 to every sum.
     */
    private static boolean isTracked(Material m) {
        return m.fluid() || m.air();
    }

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
     * §9 per-species mass gate (Spec Decision 6/12, Phase-2b; §11 air-as-species). One O(N) pass with a
     * <b>dual-index</b> conservation sum: each cell's {@code before} mass is accumulated under its
     * <b>input</b> species ({@code inMat[i]}) and its {@code after} mass under its <b>output</b> species
     * ({@code outMat[i]}), each only when that species is a <b>tracked species</b> (index != 0 and
     * {@code fluid() || air()} — §11 makes AIR a real, finite, conserved gas alongside the liquids/gas).
     * After the pass, every tracked species must be conserved within {@code ε·N}. The conservation sum is
     * <b>never exempted</b> — this is what makes it the real gate.
     *
     * <p><b>§11 — air is conserved on its own index (no air-credit).</b> The old "air-credit" /
     * "air-untracked" exemption is gone. A liquid no longer <em>consumes</em> the air it wets/falls into;
     * it <b>displaces</b> it. Each interaction balances per species directly:
     * <ul>
     *   <li><b>wetting</b> (air-in / water-out): the wetted cell SUBTRACTS its air {@code before} from
     *       air's sum (the air left the cell) and ADDS the deposited water to water's sum. The displaced
     *       air must RE-APPEAR as {@code air-out} mass in a receiver cell (same co-stepped batch) or air's
     *       conservation FAILS — exactly the §11 mass-from-nothing guard.</li>
     *   <li><b>density swap</b> (water-in / air-out below, air-in / water-out above): water conserves on
     *       water's dual index, air on air's — no cross credit needed.</li>
     *   <li>liquid sort swaps, drains, and {@code steam ↔ air} buoyancy all conserve each species on its
     *       own dual index.</li>
     * </ul>
     * It still forbids mass invention (a water→lava relabel fails because lava's after-sum exceeds its
     * before-sum while water's falls short).
     *
     * <p>Separately, each tracked-species cell is bounded on its <b>output</b> species,
     * {@code after[i] ∈ [−ε, maxMass(outMat[i]) + ε]}. The ONLY exemption is from this BOUND: a cell
     * already <b>over its own output-species cap</b> ({@code after[i] > maxMass(outMat[i])}) is a
     * transient compressed parcel — a §7/engine boil deposit (Decision 12 boil-volume) that the
     * advection pass relieves over the next steps — and skips the bound. The exemption key is
     * "over cap", NOT "species changed this step": a boiled steam cell stays steam for the multiple
     * steps it takes to relax, so a species-change key would stop exempting it after step 1 and freeze
     * the still-relaxing over-cap steam. Exempting an over-cap cell from the bound is safe precisely
     * because the dual-index conservation sum (never exempted) still prevents mass invention. VACUUM/void
     * (index 0) and solids are not tracked species and contribute to neither sum.
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
        SpeciesMassLedger ledger = new SpeciesMassLedger();
        if (!ledger.add(after, before, inMat, outMat, lut)) return false;
        return ledger.conserved();
    }

    /**
     * §9 per-cell BOUND-only check (no conservation): true iff every <b>tracked-species output</b> cell
     * (fluid/gas OR §11 air) is finite and within {@code [−ε, maxMass(outMat[i]) + ε]}, with the single
     * documented exemption — a cell already <b>over its own output-species cap</b>
     * ({@code after[i] > maxMass(outMat[i])}) is the transient §7/engine boil deposit (Decision 12
     * boil-volume) the advection pass relaxes over the next steps, so it skips the upper bound (the
     * lower/negative bound is never relaxed). VACUUM/void (index 0) and solid output cells are not tracked
     * species and are ignored. This is exactly the
     * bound that {@link SpeciesMassLedger#add} folds into the per-cell pass, lifted out standalone so
     * the Scheduler can reject a single section's illegally-shaped cells while deferring the
     * CONSERVATION decision to a batch-level {@link SpeciesMassLedger}.
     *
     * @param after  engine mass output (length N)
     * @param outMat per-cell OUTPUT species (the engine's {@code material()}); index into {@code lut}
     * @param lut    batch material table (index 0 = {@link MaterialLut#VOID})
     */
    public static boolean cellsWithinBound(float[] after, char[] outMat, List<Material> lut) {
        float cellEps = MASS_EPSILON_PER_CELL;
        for (int i = 0; i < after.length; i++) {
            if (!Float.isFinite(after[i])) return false;
            int out = outMat[i];
            // §11: air is a tracked, finite species too, so an air output cell is bounded to its own
            // max_mass (1000) exactly like a fluid. Vacuum/void (index 0) and solids are not advection
            // masses and skip the bound.
            if (out != 0 && isTracked(lut.get(out))) {
                float bound = lut.get(out).maxMass();
                if (!(after[i] > bound) && (after[i] < -cellEps || after[i] > bound + cellEps)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Batch-level §9 per-species conservation accumulator (DESIGN §10 cross-section). Runs the SAME
     * dual-index per-cell accounting as {@link #massConservedPerSpecies} (input species → sumBefore,
     * output species → sumAfter; §11 makes AIR a tracked species conserved on its OWN index — no
     * air-credit), but accumulates across MANY co-stepped sections and validates ONCE at {@code ε·(Σ N)}.
     * Cross-seam transfers between two co-stepped sections cancel in the batch sum, so a fall that moves
     * a full cell from one section into the one below — which each per-section gate would false-reject —
     * is accepted at batch scope while real fabrication (a gain with no matching donor anywhere in the
     * batch) is still rejected.
     *
     * <p>{@link #add} also runs the per-cell BOUND (over-cap exemption included) for back-compat with
     * {@link #massConservedPerSpecies}; it returns {@code false} on a non-finite or illegally-over-cap
     * cell so a single-entry ledger reproduces the per-section verdict bit-for-bit. The Scheduler, which
     * separates bound from conservation, uses {@link #cellsWithinBound} as PASS 1 and then adds only
     * bound-clean entries here. The {@code lut} must be consistent across {@link #add} calls (the batch
     * LUT); the species arrays grow to {@code lut.size()} as needed.</p>
     */
    public static final class SpeciesMassLedger {
        private double[] sumBefore = new double[0];
        private double[] sumAfter = new double[0];
        private long totalCells;

        /**
         * Accumulate one section's per-cell dual-index sums into the ledger and run the per-cell bound.
         * Returns {@code false} iff a cell is non-finite or illegally over its own output cap (matching
         * {@link #massConservedPerSpecies}); the sums are still accumulated up to (not including) the
         * offending cell only if it returns early, so callers that pre-screen with
         * {@link #cellsWithinBound} should ignore the return and rely on bound-clean input.
         *
         * <p><b>Precondition:</b> all {@code add} calls on the same ledger instance MUST pass the same
         * batch {@code lut} (or one that only GROWS — new species appended at the end). If a later call
         * passes a {@code lut} that is SMALLER than the one used to initialise the internal species
         * arrays, the ledger would silently mis-key species sums; this guard throws loudly instead.
         */
        public boolean add(float[] after, float[] before, char[] inMat, char[] outMat, List<Material> lut) {
            if (sumAfter.length > 0 && lut.size() < sumAfter.length) {
                throw new IllegalArgumentException(
                        "SpeciesMassLedger: lut shrank across add() calls (was "
                                + sumAfter.length + " species, got " + lut.size() + ")");
            }
            grow(lut.size());
            float cellEps = MASS_EPSILON_PER_CELL;
            boolean bound = true;
            for (int i = 0; i < after.length; i++) {
                if (!Float.isFinite(after[i])) { bound = false; continue; }
                int in = inMat[i];
                int out = outMat[i];
                // §11: every TRACKED species (fluid/gas OR air) is conserved on ITS OWN dual index — no
                // cross-species credit. Air is no longer "adopted-and-discarded" by a fluid; it is a real,
                // finite gas that liquid DISPLACES, so the air a wetting/swap relocates must RE-APPEAR as
                // air mass elsewhere in the (co-stepped) batch, balancing air's own sumBefore/sumAfter.
                // VACUUM/void (index 0) is no species: it contributes to neither sum.
                //   - BEFORE mass is credited under the cell's INPUT species.
                //   - AFTER  mass is credited under the cell's OUTPUT species.
                // Wetting (air-in/water-out) now SUBTRACTS that air's before from air's sum (it left this
                // cell) and ADDS the deposited water to water's sum; the displaced air must reappear in an
                // air-out receiver to conserve air. Swap (water-in/air-out) symmetrically credits the risen
                // air's after to air's own sum and the sunk water's before to water's own sum. A relabel
                // that simply makes air vanish (no air-out receiver) now FAILS air's conservation — which
                // is the whole point of the §11 fix.
                if (in != 0 && isTracked(lut.get(in))) {
                    sumBefore[in] += before[i];
                }
                if (out != 0 && isTracked(lut.get(out))) {
                    float b = lut.get(out).maxMass(); // per-species cap (canonical accessor: 0 -> defaultMass)
                    // BOUND on the output species, exempting a cell already OVER its own cap (a transient
                    // §7/engine boil deposit relaxing over the next steps). The conservation sum is NEVER
                    // exempted, so the exemption can hide an over-cap parcel but never invented mass.
                    if (!(after[i] > b) && (after[i] < -cellEps || after[i] > b + cellEps)) {
                        bound = false;
                    }
                    sumAfter[out] += after[i];
                }
            }
            totalCells += after.length;
            return bound;
        }

        /**
         * True iff every tracked species (fluid/gas + §11 air) is conserved within {@code ε · totalCells} (the same
         * tolerance {@link #massConservedPerSpecies} applies per section, summed over the whole batch).
         */
        public boolean conserved() {
            double tol = (double) MASS_EPSILON_PER_CELL * totalCells;
            for (int s = 1; s < sumAfter.length; s++) {
                if (Math.abs(sumAfter[s] - sumBefore[s]) > tol) return false;
            }
            return true;
        }

        private void grow(int speciesCount) {
            if (sumAfter.length >= speciesCount) return;
            sumAfter = java.util.Arrays.copyOf(sumAfter, speciesCount);
            sumBefore = java.util.Arrays.copyOf(sumBefore, speciesCount);
        }
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
