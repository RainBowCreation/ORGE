package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/**
 * A placement is captured (enqueued for displace-and-inject) when the placed material differs from the
 * recorded incumbent species (or the cell is untracked). A self-write (live == incumbent id, the
 * reconciler's own engine-output repaint) is skipped. {@code movable()} no longer participates in this
 * decision — it governs only post-placement flow.
 */
class PlacementInjectionPolicyTest {

    // TestMaterials.air() and .water() have finite viscosity (movable() == true).
    // TestMaterials.stone() has no viscosity set → loads as +∞ (frozen, movable() == false).
    private static Material air()   { return TestMaterials.air(); }
    private static Material water() { return TestMaterials.water(); }
    private static Material stone() { return TestMaterials.stone(); }

    // --- fluid-over-fluid (previously tested) ---

    @Test
    void waterOverAirIsDisplacement() {
        assertTrue(PlacementInjectionPolicy.isDisplacement(water(), air()));
    }

    @Test
    void sameSpeciesFluidIsNotDisplacement() {          // reconciler self-write: live == incumbent
        assertFalse(PlacementInjectionPolicy.isDisplacement(water(), water()));
    }

    @Test
    void fluidOverNullIncumbentIsDisplacement() {       // untracked cell — enqueue for durability
        assertTrue(PlacementInjectionPolicy.isDisplacement(water(), null));
    }

    @Test
    void nullLiveIsNotDisplacement() {                  // nothing placed → nothing to inject
        assertFalse(PlacementInjectionPolicy.isDisplacement(null, air()));
    }

    // --- solid cases (BUG 2: previously returned false, now must be true) ---

    @Test
    void solidOverFluidIsDisplacement() {               // placing stone into water must displace
        assertTrue(PlacementInjectionPolicy.isDisplacement(stone(), water()));
    }

    @Test
    void solidOverSolidDifferentIdIsDisplacement() {    // different solid species → displace
        // stone id != air id; both are non-movable — movable() must not affect outcome
        assertTrue(PlacementInjectionPolicy.isDisplacement(stone(), air()));
    }

    @Test
    void solidOverNullIncumbentIsDisplacement() {       // untracked cell with solid placement
        assertTrue(PlacementInjectionPolicy.isDisplacement(stone(), null));
    }

    @Test
    void sameSpeciesSolidIsNotDisplacement() {          // reconciler repaint with solid species
        assertFalse(PlacementInjectionPolicy.isDisplacement(stone(), stone()));
    }

    // --- previously-named tests updated to new contract ---

    @Test
    void sameSpeciesIsNotDisplacement() {               // alias: same id regardless of movable()
        assertFalse(PlacementInjectionPolicy.isDisplacement(water(), water()));
    }

    // --- shouldInject: ANY ORGE material is captured (same species = top-up, different = displace) ---

    @Test
    void shouldInjectSameSpecies() {                    // water-on-water now enqueues (top-up)
        assertTrue(PlacementInjectionPolicy.shouldInject(water(), water()));
    }

    @Test
    void shouldInjectDifferentSpecies() {
        assertTrue(PlacementInjectionPolicy.shouldInject(water(), air()));
    }

    @Test
    void shouldInjectUntrackedCell() {
        assertTrue(PlacementInjectionPolicy.shouldInject(water(), null));
    }

    @Test
    void shouldNotInjectNullLive() {                    // non-ORGE block → nothing to inject
        assertFalse(PlacementInjectionPolicy.shouldInject(null, water()));
    }
}
