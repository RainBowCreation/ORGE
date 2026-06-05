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

    /** SAME-SPECIES placement (e.g. a water source on a cell the engine already records as water) is now
     *  ENQUEUED as a top-up — NOT dropped. capture() is driven only from the block-PLACE wake path, so a
     *  same-species call here is a genuine placement event; the engine tops the cell up to defaultMass in
     *  place (idempotent when already full). This is the water-on-water + repeated-place fix. */
    @Test
    void sameSpeciesPlacementEnqueuesTopUp() {       // live == incumbent: a real PLACE, not a repaint
        PendingInjections q = new PendingInjections();
        Material live = water();
        int cell = 3 + 16 * 70 + 6144 * 4;
        float ambientK = 295f;

        PlacementCapture.capture(q, DIM, 0, 0, cell, live, water(), ambientK);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size(), "same-species placement is enqueued as a top-up, not dropped");
        assertEquals(water().id(), got.get(0).species());
        assertEquals(water().defaultMass(), got.get(0).mass());
        float expectT = live.hasDefaultTemperature() ? live.defaultTemperature() : ambientK;
        assertEquals(expectT, got.get(0).temperature());
    }

    @Test
    void nullLiveDoesNotEnqueue() {                  // non-ORGE block → nothing to inject
        PendingInjections q = new PendingInjections();
        PlacementCapture.capture(q, DIM, 0, 0, 100, null, water(), 295f);
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

    // ---- captureOrCancelStaleRemoval: the same-window break/flicker + same-species re-place handler ----

    /** Same-window break+replace-SAME-block fix. Re-placing the species the cell already had, when a
     *  same-window BREAK (or a transient fluid-level air-edit) already queued a removal at the cell, must
     *  CANCEL that stale removal — NOT enqueue a fresh defaultMass injection. Force-injecting a movable
     *  fluid here FABRICATES mass (the regression: water re-asserting over a transient removal injected a
     *  whole 1000 kg every slosh); letting the lone removal stand instead stomps a solid's engine cell to
     *  vacuum under a still-solid durable identity (flow-through). Cancelling does neither: the cell keeps
     *  its durable identity + stored mass, no injection. */
    @Test
    void sameSpeciesRePlaceOverPendingRemovalCancelsItAndEnqueuesNothing() {
        PendingInjections q = new PendingInjections();
        q.enqueueRemoval(DIM, 0, 0, 100);   // a same-window BREAK / air-flicker queued a removal here

        PlacementCapture.captureOrCancelStaleRemoval(q, DIM, 0, 0, 100, water(), water(), 295f);

        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty(),
                "the stale removal is cancelled and NO placement is enqueued (no vacuum stomp, no fabrication)");
    }

    /** A DIFFERENT species placed over a pending removal is a genuine displacement: it is enqueued (and
     *  supersedes the removal, last-write-wins) so the engine places the new species. */
    @Test
    void differentSpeciesOverPendingRemovalEnqueuesPlacementSupersedingRemoval() {
        PendingInjections q = new PendingInjections();
        Material stone = TestMaterials.stone();
        q.enqueueRemoval(DIM, 0, 0, 100);

        PlacementCapture.captureOrCancelStaleRemoval(q, DIM, 0, 0, 100, water(), stone, 295f);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size(), "the different-species place supersedes the removal");
        assertFalse(got.get(0).removal(), "the surviving intent is the water placement, not the removal");
        assertEquals(water().id(), got.get(0).species());
    }

    /** A non-ORGE block ({@code live == null}) over a pending removal cancels nothing and enqueues
     *  nothing — the removal stays the only intent (the block genuinely went away). */
    @Test
    void nullLiveOverPendingRemovalLeavesRemovalIntact() {
        PendingInjections q = new PendingInjections();
        q.enqueueRemoval(DIM, 0, 0, 100);

        PlacementCapture.captureOrCancelStaleRemoval(q, DIM, 0, 0, 100, null, water(), 295f);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size());
        assertTrue(got.get(0).removal(), "a non-ORGE place leaves the removal intact");
    }

    /** Without a pending removal, captureOrCancelStaleRemoval delegates to capture: a same-species place
     *  now enqueues a top-up (the engine no-ops when already full), and a real displacement still enqueues.
     *  (The cancel-and-return branch only fires when a removal IS pending — see the test above.) */
    @Test
    void noPendingRemovalDelegatesToOrdinaryCapture() {
        PendingInjections q = new PendingInjections();
        PlacementCapture.captureOrCancelStaleRemoval(q, DIM, 0, 0, 100, water(), water(), 295f);
        assertEquals(1, q.peekColumn(DIM, 0, 0).size(),
                "same-species place with no pending removal enqueues a top-up");

        PlacementCapture.captureOrCancelStaleRemoval(q, DIM, 0, 0, 101, water(), air(), 295f);
        assertEquals(2, q.peekColumn(DIM, 0, 0).size(),
                "the top-up (cell 100) and the displacement (cell 101) are both enqueued");
    }
}
