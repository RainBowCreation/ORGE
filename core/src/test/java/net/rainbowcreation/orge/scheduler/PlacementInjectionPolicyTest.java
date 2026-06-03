package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** B1/B5 decision: enqueue an injection ONLY for a genuine movable→movable placement where the new
 *  species differs from the recorded incumbent (so reconciler self-writes and solid breaks are skipped). */
class PlacementInjectionPolicyTest {

    // Use the project's shared test material factory (same helper used by other scheduler tests).
    // TestMaterials.air() and .water() have finite viscosity (movable() == true).
    // TestMaterials.stone() has no viscosity set → loads as +∞ (frozen, movable() == false).
    private static Material air()   { return TestMaterials.air(); }
    private static Material water() { return TestMaterials.water(); }
    private static Material stone() { return TestMaterials.stone(); }   // visc = +inf

    @Test
    void waterOverAirIsDisplacement() {
        assertTrue(PlacementInjectionPolicy.isDisplacement(water(), air()));
    }

    @Test
    void sameSpeciesIsNotDisplacement() {              // reconciler self-write: live == incumbent
        assertFalse(PlacementInjectionPolicy.isDisplacement(water(), water()));
    }

    @Test
    void nonMovableIncumbentIsNotDisplacement() {      // /setblock water over stone: existing seed path
        assertFalse(PlacementInjectionPolicy.isDisplacement(water(), stone()));
    }

    @Test
    void nonMovableNewSpeciesIsNotDisplacement() {     // placing stone: not an injection
        assertFalse(PlacementInjectionPolicy.isDisplacement(stone(), air()));
    }

    @Test
    void fluidOverNullIncumbentIsDisplacement() {      // bug 1: untracked cell — still enqueue so the
        // placement is made durable (otherwise a stale in-flight step stomps the live fluid → vanish).
        assertTrue(PlacementInjectionPolicy.isDisplacement(water(), null));
    }

    @Test
    void nullLiveIsNotDisplacement() {                 // nothing placed → nothing to inject
        assertFalse(PlacementInjectionPolicy.isDisplacement(null, air()));
    }
}
