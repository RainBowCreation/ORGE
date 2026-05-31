package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision 11 trigger (c): an active section that moved mass at its boundary face must wake the
 * adjacent section, or flow stops dead at a dormant border. We test the pure rule
 * {@link SeamFluxWake#neighboursToWake}: given a section key + the six per-face "did mass cross"
 * flags, it returns the adjacent keys to wake.
 */
class SeamFluxWakeTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void noFaceFluxWakesNoNeighbour() {
        SubchunkKey k = new SubchunkKey(2, 3, 4);
        var n = SeamFluxWake.neighboursToWake(k, false, false, false, false, false, false);
        assertTrue(n.isEmpty());
    }

    @Test
    void negXFaceFluxWakesTheNegXNeighbour() {
        SubchunkKey k = new SubchunkKey(2, 3, 4);
        // faces order: negX, posX, negY, posY, negZ, posZ
        var n = SeamFluxWake.neighboursToWake(k, true, false, false, false, false, false);
        assertEquals(java.util.List.of(new SubchunkKey(1, 3, 4)), n);
    }

    @Test
    void posYFaceFluxWakesTheCellAbove() {
        SubchunkKey k = new SubchunkKey(2, 3, 4);
        var n = SeamFluxWake.neighboursToWake(k, false, false, false, true, false, false);
        assertEquals(java.util.List.of(new SubchunkKey(2, 4, 4)), n);
    }

    @Test
    void multipleFacesWakeMultipleNeighbours() {
        SubchunkKey k = new SubchunkKey(0, 0, 0);
        var n = SeamFluxWake.neighboursToWake(k, true, true, false, false, false, false);
        assertTrue(n.contains(new SubchunkKey(-1, 0, 0)));
        assertTrue(n.contains(new SubchunkKey(1, 0, 0)));
        assertEquals(2, n.size());
    }
}
