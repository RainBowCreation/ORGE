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
 * ask {@link PhaseRule} whether its temperature crosses a threshold, and if the resulting
 * target block exists, record a {@link Transition}. Pure (no Minecraft world access) — the
 * per-cell {@link Material} and the block-exists check are injected, so it tests with fakes.
 * The live caller supplies {@code BuiltInRegistries.BLOCK::containsKey} and a block→material
 * lookup; see {@code MinecraftPhaseChanger}.
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

    /** One cell's transition: section-local cell index (x+16y+256z) → block id to place. */
    public record Transition(int cellIndex, Identifier blockId) {}

    private PhasePlanner() {}

    public static List<Transition> plan(float[] temperatures,
                                        float[] mass,
                                        IntFunction<Material> cellMaterial,
                                        Predicate<Identifier> blockExists) {
        List<Transition> out = new ArrayList<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            if (mass[i] <= PHASE_MIN_MASS) {
                continue; // drained/empty cell — not a fluid that can boil or freeze (Bug B)
            }
            Optional<Identifier> target = PhaseRule.targetBlock(temperatures[i], cellMaterial.apply(i));
            if (target.isPresent() && blockExists.test(target.get())) {
                out.add(new Transition(i, target.get()));
            }
        }
        return out;
    }
}
