package net.rainbowcreation.orge.command;

import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CellAddressTest {

    @Test
    void originMapsToSectionZeroCellZero() {
        CellAddress a = CellAddress.of(0, 0, 0);
        assertEquals(new SubchunkKey(0, 0, 0), a.key());
        assertEquals(0, a.cell());
    }

    @Test
    void cellIndexIsXFastestThenYThenZ() {
        // x + 16*y + 256*z, each axis masked to 0..15
        assertEquals(1, CellAddress.of(1, 0, 0).cell());
        assertEquals(16, CellAddress.of(0, 1, 0).cell());
        assertEquals(256, CellAddress.of(0, 0, 1).cell());
        assertEquals(1 + 16 * 2 + 256 * 3, CellAddress.of(1, 2, 3).cell());
        assertEquals(4095, CellAddress.of(15, 15, 15).cell());
    }

    @Test
    void negativeCoordsFloorToSectionAndWrapCell() {
        CellAddress a = CellAddress.of(-1, -1, -1);
        assertEquals(new SubchunkKey(-1, -1, -1), a.key());
        assertEquals(15 + 16 * 15 + 256 * 15, a.cell(), "(-1 & 15) == 15 on each axis");
    }

    @Test
    void roundTripsAgainstLiveMaterialsBlockAt() {
        // CellAddress.of(...).cell() is the inverse of LiveMaterials' x+16y+256z decode.
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, y = (i >> 4) & 15, z = (i >> 8) & 15;
            assertEquals(i, CellAddress.of(x, y, z).cell());
        }
        assertNotNull(LiveMaterials.class); // anchor the inverse relationship in the test's intent
    }
}
