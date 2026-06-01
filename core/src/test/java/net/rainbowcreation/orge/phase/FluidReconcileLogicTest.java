package net.rainbowcreation.orge.phase;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidReconcileLogicTest {
    @Test
    void nearFullIsSourceLevelZero() {
        // f >= 0.95 -> level 0 (full block).
        assertEquals(0, FluidReconcileLogic.levelForFraction(0.95f));
        assertEquals(0, FluidReconcileLogic.levelForFraction(1.0f));
    }

    @Test
    void partialMapsToOneThroughSeven() {
        // level = round((1 - f) * 7), clamped 1..7 for 0 < f < 0.95.
        assertEquals(7, FluidReconcileLogic.levelForFraction(0.01f)); // almost empty -> thin
        assertEquals(4, FluidReconcileLogic.levelForFraction(0.5f));
        assertTrue(FluidReconcileLogic.levelForFraction(0.9f) >= 1);
    }

    @Test
    void belowEpsilonIsRemoved() {
        // f <= 0 (mass ~ 0) -> REMOVE sentinel.
        assertEquals(FluidReconcileLogic.REMOVE, FluidReconcileLogic.levelForFraction(0f));
    }

    @Test
    void fractionFromMass() {
        assertEquals(0.5f, FluidReconcileLogic.fraction(500f, 1000f), 1e-6f);
        assertEquals(0f, FluidReconcileLogic.fraction(0f, 1000f), 0f);
        assertEquals(0f, FluidReconcileLogic.fraction(10f, 0f), 0f); // guard zero full mass
    }

    @Test
    void levelBucketIsIdentityForValidLevels() {
        // a render level IS its own bucket (REMOVE and 0..7 each their own bucket).
        assertEquals(FluidReconcileLogic.REMOVE, FluidReconcileLogic.levelBucket(FluidReconcileLogic.REMOVE));
        assertEquals(0, FluidReconcileLogic.levelBucket(0));
        assertEquals(7, FluidReconcileLogic.levelBucket(7));
    }

    @Test
    void sameBucketMeansNoWrite() {
        // two masses that both render at level 4 share a bucket -> no write needed.
        int a = FluidReconcileLogic.levelForFraction(0.5f);   // -> 4
        int b = FluidReconcileLogic.levelForFraction(0.46f);  // round((1-0.46)*7)=round(3.78)=4
        assertEquals(a, b);
        assertEquals(FluidReconcileLogic.levelBucket(a), FluidReconcileLogic.levelBucket(b));
    }

    @Test
    void crossingToRemoveIsADifferentBucket() {
        assertNotEquals(FluidReconcileLogic.levelBucket(FluidReconcileLogic.levelForFraction(0.1f)),
                        FluidReconcileLogic.levelBucket(FluidReconcileLogic.REMOVE));
    }

    // ---- species-aware render throttle (vacated-cell duplicate fix) ----

    @Test
    void speciesChangeNeverThrottles_vacatedWaterCellBecomesAir() {
        // Bug repro: engine Pass A swapped water down into the air below, so this cell's NEW
        // species is AIR rendering full (level 0) while the WORLD block is still water at level 0
        // (bucket 0). The numeric level coincides (0 == 0) but the species CHANGED (water -> air),
        // so the throttle must NOT skip -- otherwise the stale water block is never cleared.
        assertFalse(FluidReconcileLogic.throttles(false, 0, 0));
    }

    @Test
    void sameSpeciesWithinBucketStillThrottles() {
        // water -> water, both render at level 0 -> no packet (throttle preserved).
        assertTrue(FluidReconcileLogic.throttles(true, 0, 0));
    }

    @Test
    void sameSpeciesLevelChangedDoesNotThrottle() {
        // water -> water but the render level moved (3 vs current bucket 0) -> must write.
        assertFalse(FluidReconcileLogic.throttles(true, 3, 0));
    }
}
