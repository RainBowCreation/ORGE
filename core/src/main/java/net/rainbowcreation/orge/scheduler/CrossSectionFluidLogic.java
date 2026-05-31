package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.material.Material;

import java.util.List;

/**
 * Pure, headless logic for vertical cross-section fluid transfer.
 *
 * <p>When the native engine steps a 16×16×16 section it cannot write into neighbouring
 * sections, so fluid accumulates at the bottom plane (y=0) and never falls through.
 * This class bridges that gap: given the y=0 floor of an UPPER section (donor A) and
 * the y=15 top of the LOWER section (receiver B), it applies per-column transfer rules
 * identical to the interior "air-displacement swap" rule shipped in the engine:
 *
 * <ul>
 *   <li><b>SWAP</b> – donor fluid over real air: exchange the whole cell (mass+temp+species).
 *   <li><b>DEPOSIT</b> – donor fluid over the same fluid species with remaining capacity: move
 *       as much mass as fits, enthalpy-mixing temperatures.
 *   <li><b>NO-TRANSFER</b> – everything else (void, different fluid, solid, gas donors).
 * </ul>
 *
 * <p>All mutation is in-place on the six input arrays.  The method is stateless and
 * allocation-light (only the two boolean arrays in the returned record are allocated).
 *
 * <p>DESIGN: ORGE §10 Phase-2b / cross-section fall, slice-1 (vertical Y-seam).
 */
public final class CrossSectionFluidLogic {

    private CrossSectionFluidLogic() {}

    /**
     * Mass epsilon matching the native engine {@code ADV_EPS}: cells at or below this
     * threshold are treated as empty for the purposes of transfer decisions.
     */
    public static final float ADV_EPS = 1e-4f;

    /**
     * Result of a single seam settlement pass.
     *
     * @param changedA    per-column flag: true when a cell in the UPPER (A) plane was mutated
     * @param changedB    per-column flag: true when a cell in the LOWER (B) plane was mutated
     * @param massBefore  total fluid+air mass across both planes BEFORE any mutation.
     *                    The sums are fluid+air only and will be equal to {@code massAfter}
     *                    after any correct swap/deposit because this class conserves BY
     *                    CONSTRUCTION — useful for external budgeting/sanity checks, not
     *                    an independent proof of correctness.
     * @param massAfter   total fluid+air mass across both planes AFTER all mutations
     */
    public record SeamResult(boolean[] changedA, boolean[] changedB,
                             double massBefore, double massAfter) {}

    /**
     * Applies per-column vertical transfer rules between the two seam planes.
     *
     * @param massA  masses of the UPPER section's y=0 plane (length 256, x-fastest)
     * @param tempA  temperatures of the UPPER section's y=0 plane
     * @param spA    species indices of the UPPER section's y=0 plane (into {@code lut})
     * @param massB  masses of the LOWER section's y=15 plane (length 256, x-fastest)
     * @param tempB  temperatures of the LOWER section's y=15 plane
     * @param spB    species indices of the LOWER section's y=15 plane (into {@code lut})
     * @param lut    material lookup table; index 0 is the VOID sentinel
     * @return a {@link SeamResult} with change flags and pre/post mass sums
     */
    public static SeamResult settleVerticalSeam(
            float[] massA, float[] tempA, char[] spA,
            float[] massB, float[] tempB, char[] spB,
            List<Material> lut) {

        if (massA.length != NeighborHalo.FACE_CELLS || massB.length != NeighborHalo.FACE_CELLS)
            throw new IllegalArgumentException(
                    "seam plane arrays must be length " + NeighborHalo.FACE_CELLS);

        boolean[] changedA = new boolean[NeighborHalo.FACE_CELLS];
        boolean[] changedB = new boolean[NeighborHalo.FACE_CELLS];

        // Compute massBefore: sum mass of every fluid-or-air cell in both planes
        double massBefore = sumFluidAirMass(massA, spA, massB, spB, lut);

        for (int c = 0; c < NeighborHalo.FACE_CELLS; c++) {
            char spDonor = spA[c];

            // --- Donor guard ---
            // Must be a non-void, non-gas fluid with meaningful mass.
            if (spDonor == 0) continue;
            Material donor = lut.get(spDonor);
            if (!donor.fluid() || donor.gas()) continue;
            if (massA[c] <= ADV_EPS) continue;
            // Explicit non-finite guard: documents intent and catches NaN temp even
            // when NaN mass has already been filtered by the ADV_EPS check above.
            if (!Float.isFinite(massA[c]) || !Float.isFinite(tempA[c])) continue;

            char spReceiver = spB[c];

            // --- Receiver guard: skip corrupt receiver cells ---
            if (!Float.isFinite(massB[c]) || !Float.isFinite(tempB[c])) continue;

            // --- Case 1: SWAP — receiver is real air ---
            if (spReceiver != 0 && lut.get(spReceiver).air() && massB[c] > ADV_EPS) {
                // Exchange (mass, temp, species) between A[c] and B[c]
                float tmpMass = massA[c];
                float tmpTemp = tempA[c];
                char  tmpSp   = spA[c];

                massA[c] = massB[c];
                tempA[c] = tempB[c];
                spA[c]   = spB[c];

                massB[c] = tmpMass;
                tempB[c] = tmpTemp;
                spB[c]   = tmpSp;

                changedA[c] = true;
                changedB[c] = true;
                continue;
            }

            // --- Case 2: DEPOSIT — receiver is the same fluid with remaining capacity ---
            if (spReceiver == spDonor) {
                float maxMass = donor.maxMass();
                float cap = maxMass - massB[c];
                if (cap <= ADV_EPS) continue;  // no room

                float dm = Math.min(massA[c], cap);
                if (dm <= ADV_EPS) continue;  // transfer too small to matter

                // Enthalpy-mix the receiving cell's temperature only when the result
                // is positive (guards against divide-by-zero on corrupt input).
                float newMassB = massB[c] + dm;
                if (newMassB > 0f) {
                    tempB[c] = (massB[c] * tempB[c] + dm * tempA[c]) / newMassB;
                }
                massB[c] = newMassB;

                massA[c] -= dm;
                if (massA[c] <= ADV_EPS) {
                    massA[c] = 0f;
                    // KEEP spA[c] and tempA[c] unchanged (drained donor retains species)
                }

                changedA[c] = true;
                changedB[c] = true;
                // fall through to next column
            }

            // --- Case 3 (implicit): all other receivers — no transfer ---
        }

        double massAfter = sumFluidAirMass(massA, spA, massB, spB, lut);

        return new SeamResult(changedA, changedB, massBefore, massAfter);
    }

    /**
     * Sums the mass of every cell whose species is fluid or air in both planes.
     * Index-0 (void) cells always contribute 0.
     */
    private static double sumFluidAirMass(
            float[] massA, char[] spA,
            float[] massB, char[] spB,
            List<Material> lut) {
        double sum = 0.0;
        for (int c = 0; c < NeighborHalo.FACE_CELLS; c++) {
            if (spA[c] != 0) {
                Material m = lut.get(spA[c]);
                if (m.fluid() || m.air()) sum += massA[c];
            }
            if (spB[c] != 0) {
                Material m = lut.get(spB[c]);
                if (m.fluid() || m.air()) sum += massB[c];
            }
        }
        return sum;
    }
}
