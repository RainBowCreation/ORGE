package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link PhasePlanner} — the pure §7 per-section transition plan. */
class PhasePlannerTest {

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("minecraft", path); }

    private static Material water() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                0.6f, 1000f, 0f, 1000f, 0.018f,
                373.15f, 273.15f, id("air"), id("ice"), null);
    }

    private static Material air() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "air"),
                0.026f, 1005f, 0f, 1.2f, 0.029f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
    }

    private static float[] fill(float v) {
        float[] t = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, v);
        return t;
    }

    private static final Predicate<Identifier> ALL_EXIST = x -> true;

    @Test
    void uniformBoilingSectionTransitionsEveryCell() {
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), allWater, ALL_EXIST);
        assertEquals(SectionData.CELLS, plan.size());
        assertEquals(0, plan.get(0).cellIndex());
        assertEquals(id("air"), plan.get(0).blockId());
    }

    @Test
    void heterogeneousSectionTransitionsOnlyReactiveCells() {
        IntFunction<Material> mix = i -> (i % 2 == 0) ? water() : air();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), mix, ALL_EXIST);
        assertEquals(SectionData.CELLS / 2, plan.size());
        for (PhasePlanner.Transition t : plan) {
            assertEquals(0, t.cellIndex() % 2, "only even (water) cells transition");
            assertEquals(id("air"), t.blockId());
        }
    }

    @Test
    void targetBlocksThatDoNotExistAreSkipped() {
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), allWater, x -> false);
        assertTrue(plan.isEmpty(), "no transition when the target block is not registered");
    }

    @Test
    void recordsTheCorrectCellIndex() {
        float[] temps = fill(300f);
        temps[1234] = 400f;
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, allWater, ALL_EXIST);
        assertEquals(1, plan.size());
        assertEquals(1234, plan.get(0).cellIndex());
        assertEquals(id("air"), plan.get(0).blockId());
    }

    @Test
    void freezingProducesTheFreezeTarget() {
        float[] temps = fill(250f);
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, allWater, ALL_EXIST);
        assertEquals(Set.of(id("ice")), Set.copyOf(plan.stream().map(PhasePlanner.Transition::blockId).toList()));
    }
}
