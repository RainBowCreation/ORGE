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
 * exception is the <b>fluid entry point</b>: a fluid cell the store reports as empty
 * ({@code <= ADV_EPS}) is a freshly-placed/streamed fluid block the store doesn't know about
 * yet, so it is seeded once to that material's {@link Material#defaultMass()}. Solids and air
 * are never seeded.</p>
 *
 * <p>Used by both the section's own mass and the halo neighbour mass so cross-section mass
 * conservation can't diverge between the two sides of a face.</p>
 */
public final class MassSnapshot {

    /** A stored mass at or below this (kg) counts as "empty" for fluid-entry seeding. */
    public static final float ADV_EPS = 1e-3f;

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
        if (m.fluid() && stored <= ADV_EPS) {
            // Freshly-placed/streamed fluid the store hasn't seen: seed once to full.
            return m.defaultMass();
        }
        // Solids, air, and already-flowing fluid: keep the persisted mass.
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
}
