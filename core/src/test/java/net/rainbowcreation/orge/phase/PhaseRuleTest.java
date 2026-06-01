package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link PhaseRule} — the pure §7 phase-change decision. Targets are MATERIAL ids. */
class PhaseRuleTest {

    private static Identifier mat(String path) { return Identifier.fromNamespaceAndPath("orge", path); }

    /** A material whose phase targets are MATERIAL ids (e.g. {@code orge:steam}/{@code orge:ice}). */
    private static Material material(float maxTemp, Identifier maxTarget,
                                    float minTemp, Identifier minTarget) {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "x"))
                .thermalConductivity(0.6f).heatCapacity(1000f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(293f)
                .viscosity(0f)
                .maxTemp(maxTemp).minTemp(minTemp)
                .maxTarget(maxTarget).minTarget(minTarget)
                .build();
    }

    private static Material inert() {
        return material(Float.POSITIVE_INFINITY, null, Float.NEGATIVE_INFINITY, null);
    }

    @Test
    void boilsAboveBoilingPointToMaxMaterial() {
        Material water = material(373.15f, mat("steam"), 273.15f, mat("ice"));
        assertEquals(Optional.of(mat("steam")), PhaseRule.targetMaterial(400f, water));
    }

    @Test
    void freezesBelowFreezingPointToMinMaterial() {
        Material water = material(373.15f, mat("steam"), 273.15f, mat("ice"));
        assertEquals(Optional.of(mat("ice")), PhaseRule.targetMaterial(250f, water));
    }

    @Test
    void noTransitionInsideTheBand() {
        Material water = material(373.15f, mat("steam"), 273.15f, mat("ice"));
        assertEquals(Optional.empty(), PhaseRule.targetMaterial(300f, water));
    }

    @Test
    void exactThresholdsAreNoOps() {
        Material water = material(373.15f, mat("steam"), 273.15f, mat("ice"));
        assertEquals(Optional.empty(), PhaseRule.targetMaterial(373.15f, water), "boiling uses strict >");
        assertEquals(Optional.empty(), PhaseRule.targetMaterial(273.15f, water), "freezing uses strict <");
    }

    @Test
    void nullTargetsNeverTransitionEvenPastThreshold() {
        Material noBoil = material(373.15f, null, 273.15f, null);
        assertEquals(Optional.empty(), PhaseRule.targetMaterial(9999f, noBoil));
        assertEquals(Optional.empty(), PhaseRule.targetMaterial(0f, noBoil));
    }

    @Test
    void infiniteDefaultsNeverTransition() {
        assertEquals(Optional.empty(), PhaseRule.targetMaterial(5000f, inert()));
        assertEquals(Optional.empty(), PhaseRule.targetMaterial(1f, inert()));
    }

    @Test
    void boilingTakesPrecedenceWhenBothCouldFire() {
        // Contrived overlap (boil 300 < freeze 400) so BOTH branches are simultaneously true at 350 K —
        // the only way to actually exercise boiling-before-freezing precedence.
        Material m = material(300f, mat("a"), 400f, mat("b"));
        assertEquals(Optional.of(mat("a")), PhaseRule.targetMaterial(350f, m), "boiling checked first");
    }
}
