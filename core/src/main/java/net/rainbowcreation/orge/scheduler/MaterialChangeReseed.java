package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.List;

/**
 * Re-seeds a cell's stale {@code temperature}/{@code mass} when its block changed material since the
 * previous cycle (DESIGN §10 follow-on; the bucket-placed-fluid fix). The §5 store holds only
 * temp+mass, never material identity, so when a cell's block changes — air→water from a bucket, lava
 * replacing water, /setblock — the persisted values still belong to the OLD block (e.g. a formerly-air
 * cell stored {@code air.default_mass} = 1.2 kg and ambient temperature). The snapshot re-reads the
 * live material every cycle; this unit compares it to the {@link CellMaterialTracker} signature and,
 * for every cell that became a <b>different fluid</b>, overrides the stale values with the new
 * material's {@link Material#defaultMass()} and its {@link AmbientSeeder} temperature (a source's
 * {@code default_temperature}, otherwise biome ambient).
 *
 * <p>Scope is deliberately <b>fluid materials only</b>. ORGE's own phase transitions all yield
 * non-fluid blocks (water→ice/steam, lava→stone), so gating on {@code live.fluid()} leaves the
 * post-transition state §7 wrote untouched without this unit needing to know which changes were
 * ORGE's. Solids carry stale thermal mass after an external swap, but that does not drive advection
 * and is a separate, lower-impact follow-on. Pure and array-mutating, mirroring {@link MassSnapshot}.</p>
 */
public final class MaterialChangeReseed {

    private MaterialChangeReseed() {}

    /** True when {@code live} is a fluid whose id differs from {@code prior} (the cell's stored values are stale). */
    public static boolean reseeds(Identifier prior, Material live) {
        return prior != null && live.fluid() && !live.id().equals(prior);
    }

    /**
     * Overrides {@code temps}/{@code mass} in place for every cell whose live material became a
     * different fluid since {@code prior}. No-op when {@code prior == null} (the section was never
     * tracked, so the block-derived seed is already authoritative) or a cell's material is unchanged
     * or non-fluid.
     *
     * @param prior        per-cell material ids the stored values belong to, or {@code null}
     * @param matIx        the live per-cell material indices into {@code lut}
     * @param lut          the batch material table (index 0 = {@link MaterialLut#VOID})
     * @param temps        per-cell temperatures to correct (mutated)
     * @param mass         per-cell masses to correct (mutated)
     * @param biomeAmbientK the section's biome ambient temperature (K), used for non-source fluids
     */
    public static void apply(Identifier[] prior, char[] matIx, List<Material> lut,
                             float[] temps, float[] mass, float biomeAmbientK) {
        if (prior == null) {
            return;
        }
        for (int i = 0; i < SectionData.CELLS; i++) {
            Material m = lut.get(matIx[i]);
            if (reseeds(prior[i], m)) {
                temps[i] = m.hasDefaultTemperature() ? m.defaultTemperature() : biomeAmbientK;
                mass[i] = m.defaultMass();
            }
        }
    }
}
