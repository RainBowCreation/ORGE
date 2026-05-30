package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.List;

/**
 * The per-cell rule for choosing a section's input mass at snapshot time (DESIGN §10
 * advection). Mirrors {@link AmbientSeeder} for temperatures and stays pure/testable.
 *
 * <p>The store is authoritative once a section has been simulated: its per-cell mass is the
 * accumulated advection result and must be preserved, otherwise every snapshot would reset
 * fluid cells to "full" and discard real flow (the level-oscillation bug). The single
 * exception is the <b>fluid entry point</b>: a fluid cell the store reports as exactly empty
 * ({@code <= 0}) is a freshly-placed/streamed fluid block the store doesn't know about yet, so
 * it is seeded once to that material's {@link Material#defaultMass()}. A cell the engine actively
 * drained to a tiny positive residue is NOT re-seeded (it keeps draining), so fluid edges can't
 * regrow. Solids and air are never seeded.</p>
 *
 * <p>Used by both the section's own mass and the halo neighbour mass so cross-section mass
 * conservation can't diverge between the two sides of a face.</p>
 */
public final class MassSnapshot {

    private MassSnapshot() {}

    /**
     * Per-cell input-mass selection.
     *
     * @param stored      the cell's persisted/advected mass (ignored when {@code !hasSection})
     * @param matIx       the cell's material index into {@code lut}
     * @param lut         the batch material table (index 0 = {@link MaterialLut#VOID})
     * @param hasSection  whether the store already holds this section
     * @param geoMass     the block-derived default mass for this cell
     * @return the mass to feed the engine for this cell
     */
    public static float select(float stored, char matIx, List<Material> lut,
                               boolean hasSection, float geoMass) {
        if (!hasSection) {
            // First-ever simulation of this section: nothing persisted, use the block seed.
            return geoMass;
        }
        Material m = lut.get(matIx);
        if (m.fluid() && stored <= 0f) {
            // Freshly-placed/streamed fluid the store hasn't seen: seed once to full.
            //
            // The guard is exactly-empty (<= 0), NOT a fat epsilon, to avoid re-injecting mass at
            // draining fluid edges. A cell the kernel actively drained leaves one of two marks:
            //   * stored == 0 AND the reconciler has turned it to air -> next snapshot it is a
            //     non-fluid cell, so this branch never fires; or
            //   * stored > 0 (a thin positive residue the kernel hasn't yet zeroed at its own
            //     epsilon) with a fluid block still present -> NOT seeded, it keeps draining until
            //     the kernel zeroes it.
            // A never-fluid placement cell's stored mass is exactly 0.0, so <= 0 seeds precisely
            // the placement/stream entry points and eliminates the old (kernel-eps, 1e-3] re-
            // injection band that grew water edges unboundedly.
            return m.defaultMass();
        }
        // Solids, air, and already-flowing/draining fluid: keep the persisted mass.
        return stored;
    }

    /**
     * Builds a whole section's snapshot mass array from the stored mass (or the block-derived
     * geometry mass when the section has never been simulated), applying {@link #select} per
     * cell. {@code stored} may be {@code null} iff {@code !hasSection}.
     */
    public static float[] selectAll(float[] stored, char[] matIx, List<Material> lut,
                                    boolean hasSection, float[] geoMass) {
        if (!hasSection) {
            return geoMass;
        }
        float[] out = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            out[i] = select(stored[i], matIx[i], lut, true, geoMass[i]);
        }
        return out;
    }

    /**
     * Halo-face variant of {@link #selectAll}: applies the SAME per-cell {@link #select} rule, but
     * ONLY at the 256 cells of {@code face} (DESIGN §10/§2 perf follow-on). {@code matIx}/{@code geoMass}
     * are the face-only geometry from {@link GeometryAssembler#assembleFace} (populated only at the
     * face cells). The result is section-sized and, at every face cell, bit-identical to what
     * {@link #selectAll} produced — the only cells the halo ever reads — so cross-section mass
     * conservation is unchanged. When the section has never been simulated the face geometry seed is
     * used as-is (face cells already carry the block-derived mass).
     */
    public static float[] selectFace(float[] stored, char[] matIx, List<Material> lut,
                                     boolean hasSection, float[] geoMass,
                                     GeometryAssembler.Face face) {
        if (!hasSection) {
            return geoMass;
        }
        float[] out = new float[SectionData.CELLS];
        for (int k = 0; k < FACE_CELLS; k++) {
            int i = face.cellIndex(k);
            out[i] = select(stored[i], matIx[i], lut, true, geoMass[i]);
        }
        return out;
    }

    /** Cells in one 16×16 boundary plane. */
    private static final int FACE_CELLS = 256;
}
