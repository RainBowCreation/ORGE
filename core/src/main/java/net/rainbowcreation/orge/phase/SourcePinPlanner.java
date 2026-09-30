package net.rainbowcreation.orge.phase;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The re-pin pass (engine-audit C): every cell whose engine out-species is a {@code pinned}
 * material is reset to its {@code default_temperature}. The engine owns every phase change (it
 * relabels in DECODE); a source the engine relabeled away this step reads as its NEW species and is
 * therefore not re-pinned — the pin is a restoring force, not a lock. Pure — the per-cell material
 * lookup is injected; callers pass the engine's post-step species ({@link EngineOutSpecies},
 * live-block fallback).
 */
public final class SourcePinPlanner {

    /** Reset cell {@code cellIndex} to {@code temperatureK} (its material's default_temperature). */
    public record Reset(int cellIndex, float temperatureK) {}

    private SourcePinPlanner() {}

    public static List<Reset> plan(IntFunction<Material> cellMaterial) {
        List<Reset> out = new ArrayList<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            Material m = cellMaterial.apply(i);
            if (m.pinned() && m.hasDefaultTemperature()) {
                out.add(new Reset(i, m.defaultTemperature()));
            }
        }
        return out;
    }
}
