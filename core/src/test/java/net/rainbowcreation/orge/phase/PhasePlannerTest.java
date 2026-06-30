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

/** Tests for {@link PhasePlanner} — the pure §7 per-section transition plan (targets are MATERIAL ids). */
class PhasePlannerTest {

    private static Identifier mat(String path) { return Identifier.fromNamespaceAndPath("orge", path); }

    private static Material water() {
        return Material.builder(mat("water"))
                .thermalConductivity(0.6f).heatCapacity(1000f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(293f).viscosity(0f)
                .maxTemp(373.15f).minTemp(273.15f)
                .maxTarget(mat("steam")).minTarget(mat("ice"))
                .build();
    }

    private static Material air() {
        return Material.builder(mat("air"))
                .thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.029f)
                .defaultMass(1.2f).defaultTemperature(293f).viscosity(0f)
                .build();
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
        assertEquals(mat("steam"), plan.get(0).materialId());
    }

    @Test
    void heterogeneousSectionTransitionsOnlyReactiveCells() {
        IntFunction<Material> mix = i -> (i % 2 == 0) ? water() : air();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), fullMass(), mix, ALL_EXIST);
        assertEquals(SectionData.CELLS / 2, plan.size());
        for (PhasePlanner.Transition t : plan) {
            assertEquals(0, t.cellIndex() % 2, "only even (water) cells transition");
            assertEquals(mat("steam"), t.materialId());
        }
    }

    @Test
    void targetMaterialsThatDoNotExistAreSkipped() {
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), fullMass(), allWater, x -> false);
        assertTrue(plan.isEmpty(), "no transition when the target material is not registered");
    }

    @Test
    void recordsTheCorrectCellIndex() {
        float[] temps = fill(300f);
        temps[1234] = 400f;
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, fullMass(), allWater, ALL_EXIST);
        assertEquals(1, plan.size());
        assertEquals(1234, plan.get(0).cellIndex());
        assertEquals(mat("steam"), plan.get(0).materialId());
    }

    @Test
    void freezingProducesTheFreezeTarget() {
        float[] temps = fill(250f);
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, fullMass(), allWater, ALL_EXIST);
        assertEquals(Set.of(mat("ice")), Set.copyOf(plan.stream().map(PhasePlanner.Transition::materialId).toList()));
    }

    // --- Bug B: a drained/empty cell is not a fluid and must not freeze or boil. ---

    @Test
    void emptyColdCellDoesNotFreeze() {
        // A cell the native engine drained to 0 kg / 0 K. 0 K is far below water's 273.15 K
        // freeze point, so without the mass guard the threshold rule would emit an ice transition for an
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
        assertEquals(Set.of(mat("ice")), Set.copyOf(plan.stream().map(PhasePlanner.Transition::materialId).toList()));
    }

    @Test
    void realHotWaterStillBoils() {
        float[] temps = fill(400f);
        float[] mass = fill(1000f);
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, mass, allWater, ALL_EXIST);
        assertEquals(SectionData.CELLS, plan.size(), "real hot water still boils");
        assertEquals(Set.of(mat("steam")), Set.copyOf(plan.stream().map(PhasePlanner.Transition::materialId).toList()));
    }

    // --- The fused §7 threshold rule: PhasePlanner.targetMaterial (migrated from PhaseRuleTest). ---
    // Targets are MATERIAL ids; boiling checked first; strict inequalities; null/±∞ never transition.

    /** A material whose phase targets are MATERIAL ids (e.g. {@code orge:steam}/{@code orge:ice}). */
    private static Material ruleMaterial(float maxTemp, Identifier maxTarget,
                                         float minTemp, Identifier minTarget) {
        return Material.builder(mat("x"))
                .thermalConductivity(0.6f).heatCapacity(1000f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(293f)
                .viscosity(0f)
                .maxTemp(maxTemp).minTemp(minTemp)
                .maxTarget(maxTarget).minTarget(minTarget)
                .build();
    }

    private static Material inert() {
        return ruleMaterial(Float.POSITIVE_INFINITY, null, Float.NEGATIVE_INFINITY, null);
    }

    @Test
    void boilsAboveBoilingPointToMaxMaterial() {
        Material water = ruleMaterial(373.15f, mat("steam"), 273.15f, mat("ice"));
        assertEquals(java.util.Optional.of(mat("steam")), PhasePlanner.targetMaterial(400f, water));
    }

    @Test
    void freezesBelowFreezingPointToMinMaterial() {
        Material water = ruleMaterial(373.15f, mat("steam"), 273.15f, mat("ice"));
        assertEquals(java.util.Optional.of(mat("ice")), PhasePlanner.targetMaterial(250f, water));
    }

    @Test
    void noTransitionInsideTheBand() {
        Material water = ruleMaterial(373.15f, mat("steam"), 273.15f, mat("ice"));
        assertEquals(java.util.Optional.empty(), PhasePlanner.targetMaterial(300f, water));
    }

    @Test
    void exactThresholdsAreNoOps() {
        Material water = ruleMaterial(373.15f, mat("steam"), 273.15f, mat("ice"));
        assertEquals(java.util.Optional.empty(), PhasePlanner.targetMaterial(373.15f, water), "boiling uses strict >");
        assertEquals(java.util.Optional.empty(), PhasePlanner.targetMaterial(273.15f, water), "freezing uses strict <");
    }

    @Test
    void nullTargetsNeverTransitionEvenPastThreshold() {
        Material noBoil = ruleMaterial(373.15f, null, 273.15f, null);
        assertEquals(java.util.Optional.empty(), PhasePlanner.targetMaterial(9999f, noBoil));
        assertEquals(java.util.Optional.empty(), PhasePlanner.targetMaterial(0f, noBoil));
    }

    @Test
    void infiniteDefaultsNeverTransition() {
        assertEquals(java.util.Optional.empty(), PhasePlanner.targetMaterial(5000f, inert()));
        assertEquals(java.util.Optional.empty(), PhasePlanner.targetMaterial(1f, inert()));
    }

    @Test
    void boilingTakesPrecedenceWhenBothCouldFire() {
        // Contrived overlap (boil 300 < freeze 400) so BOTH branches are simultaneously true at 350 K —
        // the only way to actually exercise boiling-before-freezing precedence.
        Material m = ruleMaterial(300f, mat("a"), 400f, mat("b"));
        assertEquals(java.util.Optional.of(mat("a")), PhasePlanner.targetMaterial(350f, m), "boiling checked first");
    }
}
