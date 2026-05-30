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
 */
public final class PhasePlanner {

    /** One cell's transition: section-local cell index (x+16y+256z) → block id to place. */
    public record Transition(int cellIndex, Identifier blockId) {}

    private PhasePlanner() {}

    public static List<Transition> plan(float[] temperatures,
                                        IntFunction<Material> cellMaterial,
                                        Predicate<Identifier> blockExists) {
        List<Transition> out = new ArrayList<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            Optional<Identifier> target = PhaseRule.targetBlock(temperatures[i], cellMaterial.apply(i));
            if (target.isPresent() && blockExists.test(target.get())) {
                out.add(new Transition(i, target.get()));
            }
        }
        return out;
    }
}
