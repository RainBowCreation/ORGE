package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** The pure capture step: given the live placed material and the recorded incumbent material at a
 *  cell, enqueue an intent iff it is a displacement placement (delegating to the policy). */
class PlacementCaptureTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static Material air()   { return TestMaterials.air(); }
    private static Material water() { return TestMaterials.water(); }

    @Test
    void displacementPlacementEnqueuesIntentWithNewSpeciesSeed() {
        PendingInjections q = new PendingInjections();
        Material live = water();
        int cell = 3 + 16 * 70 + 6144 * 4;
        float ambientK = 295f;

        PlacementCapture.capture(q, DIM, 0, 0, cell, live, air(), ambientK);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size());
        assertEquals(water().id(), got.get(0).species());        // place the NEW species
        assertEquals(water().defaultMass(), got.get(0).mass());  // its defaultMass seed
        // temperature = material default if present, else biome ambient:
        float expectT = live.hasDefaultTemperature() ? live.defaultTemperature() : ambientK;
        assertEquals(expectT, got.get(0).temperature());
    }

    @Test
    void selfWriteDoesNotEnqueue() {                 // live == incumbent (reconciler repaint)
        PendingInjections q = new PendingInjections();
        PlacementCapture.capture(q, DIM, 0, 0, 100, water(), water(), 295f);
        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty());
    }

    @Test
    void airOverSolidIsDisplacement() {               // different ids → enqueue (movable() irrelevant)
        PendingInjections q = new PendingInjections();
        Material stone = TestMaterials.stone();
        PlacementCapture.capture(q, DIM, 0, 0, 100, air(), stone, 295f);
        assertEquals(1, q.peekColumn(DIM, 0, 0).size());
        assertEquals(air().id(), q.peekColumn(DIM, 0, 0).get(0).species());
    }

    /** Same-window break+replace-SAME-block fix: re-placing the species the cell already had is normally
     *  a self-write no-op, but when a same-window BREAK already queued a removal at that cell the re-place
     *  MUST be captured — otherwise the lone removal stomps the engine cell to vacuum while the durable
     *  identity store keeps the solid, and neighbours flow THROUGH the phantom hole. With the removal
     *  pending, the re-place is treated as a placement-into-vacuum and supersedes the removal. */
    @Test
    void rePlaceSameSpeciesOntoPendingRemovalIsCaptured() {
        PendingInjections q = new PendingInjections();
        q.enqueueRemoval(DIM, 0, 0, 100);   // the same-window BREAK queued a removal at this cell

        PlacementCapture.capture(q, DIM, 0, 0, 100, water(), water(), 295f, /*pendingRemoval=*/true);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size(), "the re-place supersedes the removal with a single placement intent");
        assertFalse(got.get(0).removal(), "the surviving intent is a placement, not the break removal");
        assertEquals(water().id(), got.get(0).species());
        assertEquals(water().defaultMass(), got.get(0).mass());
    }

    /** The pending-removal force-capture must still respect a non-ORGE block: {@code live == null} has
     *  nothing to place, so the break removal stays the only queued intent. */
    @Test
    void pendingRemovalDoesNotForceCaptureOfNonOrgeBlock() {
        PendingInjections q = new PendingInjections();
        q.enqueueRemoval(DIM, 0, 0, 100);

        PlacementCapture.capture(q, DIM, 0, 0, 100, null, water(), 295f, /*pendingRemoval=*/true);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size());
        assertTrue(got.get(0).removal(), "a non-ORGE place leaves the break removal intact");
    }

    /** Without a pending removal, a steady-state self-write (reconciler repaint) is still NOT enqueued —
     *  the fix is scoped to the same-window break+place collapse only. */
    @Test
    void selfWriteWithoutPendingRemovalStillDoesNotEnqueue() {
        PendingInjections q = new PendingInjections();
        PlacementCapture.capture(q, DIM, 0, 0, 100, water(), water(), 295f, /*pendingRemoval=*/false);
        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty());
    }
}
