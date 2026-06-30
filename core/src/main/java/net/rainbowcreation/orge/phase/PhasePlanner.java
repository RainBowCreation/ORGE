package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Builds one section's phase-change plan: for each of the {@value SectionData#CELLS} cells,
 * apply the §7 threshold rule ({@link #targetMaterial}) to its temperature, and if the resulting
 * target <b>material</b> exists, record a {@link Transition}. Pure (no Minecraft world access) —
 * the per-cell {@link Material} and the material-exists check are injected, so it tests with fakes.
 * The planner works purely in material ids; it does NOT resolve representative blocks (that is the
 * separate material → {@code representative_block} lookup done by the changer). The live caller
 * supplies a material-exists predicate over the active registry; see {@code MinecraftPhaseChanger}.
 *
 * <p>Cells with essentially no mass are skipped before the rule is evaluated (Bug B): when the
 * native engine fully drains a falling-water cell it zeroes both mass and temperature, and a
 * 0 K reading would otherwise spuriously "freeze" an empty cell to ice. An empty/drained cell
 * is not a fluid that can boil or freeze, so {@link #PHASE_MIN_MASS} gates the rule.</p>
 */
public final class PhasePlanner {

    /**
     * Minimum cell mass (kg) for a phase transition to be considered. Below this the cell is
     * treated as drained/empty and skipped, with no rule evaluation. Chosen to be above both a
     * truly drained 0 kg cell and the ~1.2 kg air-residue class (live air's default_mass), yet
     * well below a real shallow puddle, so genuine thin water still freezes/boils. 5 kg sits in
     * that gap (air ≈ 1.2 kg ≪ 5 kg ≪ a real fluid cell's hundreds–1000 kg).
     */
    public static final float PHASE_MIN_MASS = 5f;

    /** One cell's transition: section-local cell index (x+16y+256z) → target MATERIAL id. */
    public record Transition(int cellIndex, Identifier materialId) {}

    private PhasePlanner() {}

    /**
     * The pure §7 phase-change threshold rule: given a cell's new temperature and its current
     * {@link Material}, return the id of the <b>material</b> it should become, or empty. Boiling is
     * checked first; both tests use strict inequalities, so a cell exactly at a threshold is a no-op.
     * Null targets / ±∞ default thresholds (the record defaults) never transition.
     *
     * <p>{@code maxTarget}/{@code minTarget} are <b>material ids</b> (e.g. {@code orge:steam},
     * {@code orge:ice}) — the cell's new identity. The block actually drawn is a <em>separate</em>
     * material → {@code representative_block} lookup done by the changer ({@code PhaseChangeDecider});
     * identity lives in the material, never in the block. No Minecraft world access — fully
     * unit-testable.</p>
     *
     * <p>Assumes a finite temperature — §9 ({@code StepValidator}) replaces any NaN/±Inf before the
     * scheduler writes back, so this runs only on clean values.</p>
     */
    public static Optional<Identifier> targetMaterial(float temperatureK, Material current) {
        if (current.maxTarget() != null && temperatureK > current.maxTemp()) {
            return Optional.of(current.maxTarget());
        }
        if (current.minTarget() != null && temperatureK < current.minTemp()) {
            return Optional.of(current.minTarget());
        }
        return Optional.empty();
    }

    /**
     * @param materialExists true iff the target MATERIAL id is registered in the active material
     *                       registry — a transition to an unknown material is dropped. (This is a
     *                       material-exists check, NOT a block-exists check; the block to draw is
     *                       resolved later via the changer's material → {@code representative_block}
     *                       lookup.)
     */
    public static List<Transition> plan(float[] temperatures,
                                        float[] mass,
                                        IntFunction<Material> cellMaterial,
                                        Predicate<Identifier> materialExists) {
        List<Transition> out = new ArrayList<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            if (mass[i] <= PHASE_MIN_MASS) {
                continue; // drained/empty cell — not a fluid that can boil or freeze (Bug B)
            }
            Optional<Identifier> target = targetMaterial(temperatures[i], cellMaterial.apply(i));
            if (target.isPresent() && materialExists.test(target.get())) {
                out.add(new Transition(i, target.get()));
            }
        }
        return out;
    }
}
