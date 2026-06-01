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

    /** Index of the void/empty sentinel in every batch LUT ({@link MaterialLut#VOID}). */
    private static final char VOID_IX = 0;

    private MaterialChangeReseed() {}

    /** True when {@code live} is a fluid whose id differs from {@code prior} (the cell's stored values are stale). */
    public static boolean reseeds(Identifier prior, Material live) {
        return prior != null && live.fluid() && !live.id().equals(prior);
    }

    /**
     * True when a cell underwent a RUNTIME block→air transition (§11 Phase A; Task M4) — its
     * previously-tracked block ({@code prior}) was a real, non-air material and the cell's live
     * material is now first-class {@link Material#air()} air. Such a cell must become <b>vacuum</b>
     * (mass 0, the void sentinel), NOT 1.2 kg air-from-nothing: breaking a block opens empty volume
     * that the engine's gas volume-fill refills from real neighbouring air (conserved).
     *
     * <p>Gated on {@code prior != null}: a never-tracked section ({@code prior == null}) is chunk-load /
     * world-gen seeding — the world comes WITH its air, so its 1.2 kg block-derived seed is left alone.
     * Gated on {@code !prior.equals(live air id)}: an air→air cell (e.g. one the engine just filled with
     * air, recorded from the engine OUTPUT signature) is NOT re-vacuumed, so the reseed guard never
     * fights the gas fill ([[orge-reseed-misfire-fix]]).</p>
     */
    public static boolean voids(Identifier prior, Material live) {
        return prior != null && live.air() && !live.id().equals(prior);
    }

    /**
     * Overrides {@code matIx}/{@code temps}/{@code mass} in place per cell:
     * <ul>
     *   <li>a cell that became a DIFFERENT FLUID since {@code prior} (bucket, /setblock) is reseeded
     *       to the new fluid's {@link Material#defaultMass()} + source/ambient temperature;</li>
     *   <li>a cell that underwent a RUNTIME block→air transition (§11 Phase A; Task M4) is set to
     *       VACUUM — the void sentinel (matIx 0) at 0 mass — so a broken block opens empty volume the
     *       engine refills from neighbouring air, rather than seeding 1.2 kg air from nothing.</li>
     * </ul>
     * No-op when {@code prior == null} (the section was never tracked, so the block-derived / world-gen
     * seed is already authoritative — chunk-load air stays 1.2 kg) or a cell's material is unchanged.
     *
     * @param prior        per-cell material ids the stored values belong to, or {@code null}
     * @param matIx        the live per-cell material indices into {@code lut} (mutated: a broken
     *                     block→air cell is rewritten to the void sentinel {@code 0})
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
            } else if (voids(prior[i], m)) {
                // §11 Phase A (Task M4): a broken block (real material → air) opens empty volume.
                // Write VACUUM — the void sentinel (matIx 0) at 0 mass — NOT 1.2 kg air from nothing.
                // The engine's gas volume-fill refills it from neighbouring air, conserved. Temperature
                // is left as-is: vacuum carries no species, and the engine treats matIx 0 as void.
                matIx[i] = VOID_IX;
                mass[i] = 0f;
            }
        }
    }
}
