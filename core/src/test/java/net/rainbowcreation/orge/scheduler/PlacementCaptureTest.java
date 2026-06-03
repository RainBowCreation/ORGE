package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void breakToAirOverSolidDoesNotEnqueue() {       // incumbent non-movable -> existing seed path
        PendingInjections q = new PendingInjections();
        Material stone = TestMaterials.stone();
        PlacementCapture.capture(q, DIM, 0, 0, 100, air(), stone, 295f);
        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty());
    }
}
