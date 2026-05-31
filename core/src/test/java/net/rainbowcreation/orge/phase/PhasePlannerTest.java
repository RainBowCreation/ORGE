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

    /** A full-mass (1000 kg) section, so the mass guard never trips in temperature-only tests. */
    private static float[] fullMass() {
        return fill(1000f);
    }

    private static final Predicate<Identifier> ALL_EXIST = x -> true;

    @Test
    void uniformBoilingSectionTransitionsEveryCell() {
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), fullMass(), allWater, ALL_EXIST);
        assertEquals(SectionData.CELLS, plan.size());
        assertEquals(0, plan.get(0).cellIndex());
        assertEquals(id("air"), plan.get(0).blockId());
    }

    @Test
    void heterogeneousSectionTransitionsOnlyReactiveCells() {
        IntFunction<Material> mix = i -> (i % 2 == 0) ? water() : air();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), fullMass(), mix, ALL_EXIST);
        assertEquals(SectionData.CELLS / 2, plan.size());
        for (PhasePlanner.Transition t : plan) {
            assertEquals(0, t.cellIndex() % 2, "only even (water) cells transition");
            assertEquals(id("air"), t.blockId());
        }
    }

    @Test
    void targetBlocksThatDoNotExistAreSkipped() {
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), fullMass(), allWater, x -> false);
        assertTrue(plan.isEmpty(), "no transition when the target block is not registered");
    }

    @Test
    void recordsTheCorrectCellIndex() {
        float[] temps = fill(300f);
        temps[1234] = 400f;
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, fullMass(), allWater, ALL_EXIST);
        assertEquals(1, plan.size());
        assertEquals(1234, plan.get(0).cellIndex());
        assertEquals(id("air"), plan.get(0).blockId());
    }

    @Test
    void freezingProducesTheFreezeTarget() {
        float[] temps = fill(250f);
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, fullMass(), allWater, ALL_EXIST);
        assertEquals(Set.of(id("ice")), Set.copyOf(plan.stream().map(PhasePlanner.Transition::blockId).toList()));
    }

    // --- Bug B: a drained/empty cell is not a fluid and must not freeze or boil. ---

    @Test
    void emptyColdCellDoesNotFreeze() {
        // A cell the native engine drained to 0 kg / 0 K. 0 K is far below water's 273.15 K
        // freeze point, so without the mass guard PhaseRule would emit an ice transition for an
        // empty cell (the ghost-ice bug). With the guard, no transition.
        float[] temps = fill(0f);
        float[] mass = fill(0f);
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, mass, allWater, ALL_EXIST);
        assertTrue(plan.isEmpty(), "a 0 kg / 0 K drained cell must not transition");
    }

    @Test
    void realColdWaterStillFreezes() {
        float[] temps = fill(250f);
        float[] mass = fill(1000f);
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, mass, allWater, ALL_EXIST);
        assertEquals(SectionData.CELLS, plan.size(), "real cold water still freezes");
        assertEquals(Set.of(id("ice")), Set.copyOf(plan.stream().map(PhasePlanner.Transition::blockId).toList()));
    }

    @Test
    void realHotWaterStillBoils() {
        float[] temps = fill(400f);
        float[] mass = fill(1000f);
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, mass, allWater, ALL_EXIST);
        assertEquals(SectionData.CELLS, plan.size(), "real hot water still boils");
        assertEquals(Set.of(id("air")), Set.copyOf(plan.stream().map(PhasePlanner.Transition::blockId).toList()));
    }
}
