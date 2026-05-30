package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link PhaseRule} — the pure §7 phase-change decision. */
class PhaseRuleTest {

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("minecraft", path); }

    private static Material mat(float boilingPoint, Identifier boilingTarget,
                               float freezingPoint, Identifier freezingTarget) {
        // id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass, then the phase fields:
        return new Material(Identifier.fromNamespaceAndPath("orge", "x"),
                0.6f, 1000f, 0f, 1000f, 0.018f,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, null);
    }

    private static Material inert() {
        return mat(Float.POSITIVE_INFINITY, null, Float.NEGATIVE_INFINITY, null);
    }

    @Test
    void boilsAboveBoilingPoint() {
        Material water = mat(373.15f, id("air"), 273.15f, id("ice"));
        assertEquals(Optional.of(id("air")), PhaseRule.targetBlock(400f, water));
    }

    @Test
    void freezesBelowFreezingPoint() {
        Material water = mat(373.15f, id("air"), 273.15f, id("ice"));
        assertEquals(Optional.of(id("ice")), PhaseRule.targetBlock(250f, water));
    }

    @Test
    void noTransitionInsideTheBand() {
        Material water = mat(373.15f, id("air"), 273.15f, id("ice"));
        assertEquals(Optional.empty(), PhaseRule.targetBlock(300f, water));
    }

    @Test
    void exactThresholdsAreNoOps() {
        Material water = mat(373.15f, id("air"), 273.15f, id("ice"));
        assertEquals(Optional.empty(), PhaseRule.targetBlock(373.15f, water), "boiling uses strict >");
        assertEquals(Optional.empty(), PhaseRule.targetBlock(273.15f, water), "freezing uses strict <");
    }

    @Test
    void nullTargetsNeverTransitionEvenPastThreshold() {
        Material noBoil = mat(373.15f, null, 273.15f, null);
        assertEquals(Optional.empty(), PhaseRule.targetBlock(9999f, noBoil));
        assertEquals(Optional.empty(), PhaseRule.targetBlock(0f, noBoil));
    }

    @Test
    void infiniteDefaultsNeverTransition() {
        assertEquals(Optional.empty(), PhaseRule.targetBlock(5000f, inert()));
        assertEquals(Optional.empty(), PhaseRule.targetBlock(1f, inert()));
    }

    @Test
    void boilingTakesPrecedenceWhenBothCouldFire() {
        // Contrived overlap (boil 300 < freeze 400) so BOTH branches are simultaneously true at 350 K —
        // the only way to actually exercise boiling-before-freezing precedence.
        Material m = mat(300f, id("a"), 400f, id("b"));
        assertEquals(Optional.of(id("a")), PhaseRule.targetBlock(350f, m), "boiling checked first");
    }
}
