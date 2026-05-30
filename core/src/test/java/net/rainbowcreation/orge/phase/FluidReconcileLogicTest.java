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
}
