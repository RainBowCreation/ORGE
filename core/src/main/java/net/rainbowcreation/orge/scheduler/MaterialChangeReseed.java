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
 * for every cell that became a <b>different fluid</b>, corrects the stale temperature to its
 * {@link AmbientSeeder} value (a source's {@code default_temperature}, otherwise biome ambient) and
 * <b>clears the stale stored mass to 0</b> — it does NOT fabricate {@link Material#defaultMass()} here.
 * The single surviving mass seed lives in {@link ColumnAssembler} ({@code fluid && stored <= 0 ⇒
 * defaultMass}); clearing to 0 routes a freshly-changed fluid cell through that one seed, so the Java
 * layer never fabricates mass on a material change (DESIGN 2026-06-01 §6, R3).
 *
 * <p>Scope is deliberately <b>fluid materials only</b>. ORGE's own phase transitions all yield
 * non-fluid blocks (water→ice/steam, lava→stone), so gating on {@code live.fluid()} leaves the
 * post-transition state §7 wrote untouched without this unit needing to know which changes were
 * ORGE's. Solids carry stale thermal mass after an external swap, but that does not drive advection
 * and is a separate, lower-impact follow-on. Pure and array-mutating.</p>
 */
public final class MaterialChangeReseed {

    /** Index of the void/empty sentinel in every batch LUT ({@link MaterialLut#VOID}). */
    private static final char VOID_IX = 0;

    private MaterialChangeReseed() {}

    /** True when {@code live} is a movable material whose id differs from {@code prior} (stored values are stale). */
    public static boolean reseeds(Identifier prior, Material live) {
        return prior != null && live.movable() && !live.id().equals(prior);
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
    // TODO(Task 1.2/3.x): the old air/fluid distinction is gone (air is now just a movable gas), so
    // this collapses to movable() like reseeds(). With reseeds() checked first in apply(), the void
    // branch is currently shadowed; the broken-block→vacuum policy is reworked when the new engine
    // advection lands. Mapped to movable() to preserve compilation + the §11 finite-gas model.
    public static boolean voids(Identifier prior, Material live) {
        return prior != null && live.movable() && !live.id().equals(prior);
    }

    /**
     * Overrides {@code matIx}/{@code temps}/{@code mass} in place per cell:
     * <ul>
     *   <li>a cell that became a DIFFERENT FLUID since {@code prior} (bucket, /setblock) has its stale
     *       temperature corrected to the new fluid's source/ambient temperature and its stale stored
     *       mass <b>cleared to 0</b> — it does NOT fabricate {@code defaultMass} here. Clearing to 0
     *       routes the cell through the single surviving fresh-fluid seed in {@link ColumnAssembler}
     *       ({@code fluid && stored <= 0 ⇒ defaultMass}), so "1 bucket = 1000 kg" stays an entry point
     *       with exactly one mass seed in the pipeline (DESIGN 2026-06-01 §6, R3: the Java layer never
     *       fabricates mass on a material change);</li>
     *   <li>a cell that underwent a RUNTIME block→air transition (§11 Phase A; Task M4) is set to
     *       VACUUM — the void sentinel (matIx 0) at 0 mass — so a broken block opens empty volume the
     *       engine refills from neighbouring air, rather than seeding 1.2 kg air from nothing.</li>
     * </ul>
     * No-op when {@code prior == null} (the section was never tracked, so the block-derived / world-gen
     * seed is already authoritative — chunk-load air stays 1.2 kg) or a cell's material is unchanged.
     *
     * <p>Neither branch creates mass: the fluid-change branch only clears stale mass (the real seed
     * lives in {@link ColumnAssembler}), and the void branch zeroes a broken cell. Temperature is not a
     * conserved species, so seeding a fluid-change cell's temperature here is correct (a bucket of lava
     * must reach its pinned {@code default_temperature}).</p>
     *
     * @param prior        per-cell material ids the stored values belong to, or {@code null}
     * @param matIx        the live per-cell material indices into {@code lut} (mutated: a broken
     *                     block→air cell is rewritten to the void sentinel {@code 0})
     * @param lut          the batch material table (index 0 = {@link MaterialLut#VOID})
     * @param temps        per-cell temperatures to correct (mutated)
     * @param mass         per-cell masses to correct (mutated: a changed-fluid cell is cleared to 0 so
     *                     {@link ColumnAssembler}'s seed re-fills it to {@code defaultMass})
     * @param biomeAmbientK the section's biome ambient temperature (K), used for non-source fluids
     */
    public static void apply(Identifier[] prior, char[] matIx, List<Material> lut,
                             float[] temps, float[] mass, float biomeAmbientK) {
        if (prior == null) {
            return;
        }
        for (int i = 0; i < SectionData.CELLS; i++) {
            // A cell that IS the void sentinel is empty space, not a fluid that moved in: never reseed
            // it. (Under the canonical schema VOID is now a finite-viscosity — i.e. movable — fluid, so
            // without this guard reseeds()/voids() would fire on it; matIx 0 is the unambiguous test.)
            if (matIx[i] == VOID_IX) {
                continue;
            }
            Material m = lut.get(matIx[i]);
            if (reseeds(prior[i], m)) {
                // A different fluid moved in (bucket, /setblock). Correct the stale temperature, but do
                // NOT fabricate mass here: clear the stale stored mass to 0 so ColumnAssembler's single
                // fresh-fluid seed (stored <= 0 ⇒ defaultMass) fills it. Exactly one mass seed survives.
                temps[i] = m.hasDefaultTemperature() ? m.defaultTemperature() : biomeAmbientK;
                mass[i] = 0f;
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
