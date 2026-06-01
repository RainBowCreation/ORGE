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
 * for every cell that became a <b>different movable material</b>, corrects the stale temperature to its
 * {@link AmbientSeeder} value (a source's {@code default_temperature}, otherwise biome ambient) and
 * <b>clears the stale stored mass to 0</b> — it does NOT fabricate {@link Material#defaultMass()} here.
 * The single surviving mass seed lives in {@link ColumnAssembler} ({@code movable && stored <= 0 ⇒
 * defaultMass}); clearing to 0 routes a freshly-changed movable cell through that one seed, so the Java
 * layer never fabricates mass on a material change (DESIGN 2026-06-01 §6, R3).
 *
 * <p>Scope is the unified-model <b>movability gate</b> ({@code live.movable()} — viscosity finite):
 * any cell that became a different movable material (water, air, steam, …) is re-seeded. ORGE's cold
 * phase targets are <b>frozen</b> (ice/stone have no viscosity, so {@code +inf}), so {@code movable()}
 * naturally leaves the post-transition state §7 wrote untouched, without this unit needing to know
 * which changes were ORGE's. A genuine void/vacuum cell (matIx 0) is excluded by an explicit guard:
 * VOID is itself a finite-viscosity (movable) sentinel, so without the guard it would spuriously
 * reseed. Frozen solids carry stale thermal mass after an external swap, but that does not drive
 * advection and is a separate, lower-impact follow-on. Pure and array-mutating.</p>
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
     * Overrides {@code temps}/{@code mass} in place per cell. A cell that became a DIFFERENT movable
     * material since {@code prior} (bucket, /setblock, or a broken block whose live material is now
     * AIR) has its stale temperature corrected to the new material's source/ambient temperature and its
     * stale stored mass <b>cleared to 0</b> — it does NOT fabricate {@code defaultMass} here. Clearing
     * to 0 routes the cell through the single surviving fresh-fluid seed in {@link ColumnAssembler}
     * ({@code movable && stored <= 0 ⇒ defaultMass}), so "1 bucket = 1000 kg" (and "broken block → air's
     * default") stays an entry point with exactly one mass seed in the pipeline (DESIGN 2026-06-01 §6,
     * R3: the Java layer never fabricates mass on a material change).
     *
     * <p>Under the unified model breaking a block spawns AIR — a movable gas — not a VOID sentinel, so
     * that case is the same {@code reseeds()} path: the cell KEEPS its live air material and only its
     * stale mass is cleared. The obsolete broken-block→VACUUM (matIx→void) policy is gone (Task 4.1).</p>
     *
     * <p>No-op when {@code prior == null} (the section was never tracked, so the block-derived / world-gen
     * seed is already authoritative — chunk-load air stays 1.2 kg) or a cell's material is unchanged. The
     * branch never creates mass (it only clears stale mass; the real seed lives in {@link
     * ColumnAssembler}). Temperature is not a conserved species, so seeding a changed cell's temperature
     * here is correct (a bucket of lava must reach its pinned {@code default_temperature}).</p>
     *
     * @param prior        per-cell material ids the stored values belong to, or {@code null}
     * @param matIx        the live per-cell material indices into {@code lut} (read-only here)
     * @param lut          the batch material table (index 0 = {@link MaterialLut#VOID})
     * @param temps        per-cell temperatures to correct (mutated)
     * @param mass         per-cell masses to correct (mutated: a changed-material cell is cleared to 0 so
     *                     {@link ColumnAssembler}'s seed re-fills it to {@code defaultMass})
     * @param biomeAmbientK the section's biome ambient temperature (K), used for non-source materials
     */
    public static void apply(Identifier[] prior, char[] matIx, List<Material> lut,
                             float[] temps, float[] mass, float biomeAmbientK) {
        if (prior == null) {
            return;
        }
        for (int i = 0; i < SectionData.CELLS; i++) {
            // A cell that IS the void sentinel is empty space, not a material that moved in: never reseed
            // it. (Under the canonical schema VOID is a finite-viscosity — i.e. movable — fluid, so
            // without this guard reseeds() would fire on it; matIx 0 is the unambiguous test.)
            if (matIx[i] == VOID_IX) {
                continue;
            }
            Material m = lut.get(matIx[i]);
            if (reseeds(prior[i], m)) {
                // A different movable material moved in (bucket, /setblock, or a broken block now AIR).
                // Correct the stale temperature, but do NOT fabricate mass here: clear the stale stored
                // mass to 0 so ColumnAssembler's single fresh-fluid seed (stored <= 0 ⇒ defaultMass)
                // fills it. Exactly one mass seed survives in the pipeline.
                temps[i] = m.hasDefaultTemperature() ? m.defaultTemperature() : biomeAmbientK;
                mass[i] = 0f;
            }
        }
    }
}
