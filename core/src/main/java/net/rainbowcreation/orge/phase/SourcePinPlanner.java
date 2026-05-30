package net.rainbowcreation.orge.phase;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The conditional re-pin pass (engine-audit C): after the §7 phase plan is computed,
 * every cell that is a {@code pinned} material AND did <em>not</em> transition this step
 * is reset to its {@code default_temperature}. A source overwhelmed past its own threshold
 * transitions (it appears in {@code transitions}) and is therefore left alone — the pin is a
 * restoring force, not a lock. Pure — the per-cell material lookup is injected; callers pass
 * the pre-swap block materials so a surviving source still reads as its source material.
 */
public final class SourcePinPlanner {

    /** Reset cell {@code cellIndex} to {@code temperatureK} (its material's default_temperature). */
    public record Reset(int cellIndex, float temperatureK) {}

    private SourcePinPlanner() {}

    public static List<Reset> plan(IntFunction<Material> cellMaterial,
                                   List<PhasePlanner.Transition> transitions) {
        boolean[] transitioned = new boolean[SectionData.CELLS];
        for (PhasePlanner.Transition t : transitions) {
            transitioned[t.cellIndex()] = true;
        }
        List<Reset> out = new ArrayList<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            if (transitioned[i]) {
                continue;
            }
            Material m = cellMaterial.apply(i);
            if (m.pinned() && m.hasDefaultTemperature()) {
                out.add(new Reset(i, m.defaultTemperature()));
            }
        }
        return out;
    }
}
