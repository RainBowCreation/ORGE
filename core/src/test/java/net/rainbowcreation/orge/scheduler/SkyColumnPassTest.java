package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static net.rainbowcreation.orge.scheduler.SkyTestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@link SkyColumnPass}: the reusable top-down sky heightmap walk and its grey-body exposure weights. */
class SkyColumnPassTest {

    private record Hit(SubchunkKey key, int cell, double w) {}

    private static List<Hit> walk(SectionStore store) {
        List<Hit> hits = new ArrayList<>();
        SkyColumnPass.walk(store, 0, 0, lookup(), (key, data, cell, w) -> hits.add(new Hit(key, cell, w)));
        return hits;
    }

    private static List<Hit> at(List<Hit> hits, int x, int z) {
        return hits.stream().filter(h -> (h.cell & 15) == x && (h.cell >> 8) == z).toList();
    }

    @Test
    void skyPassesThroughAirAndTheTopStoneCellTakesItsEmissivity(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);

        List<Hit> hits = at(walk(store), 3, 5);
        double eps = mat(STONE).emissivity();
        // Air (ε 0) above is never visited; the first stone cell (section 4, y 7) takes ε of the sky.
        assertEquals(new SubchunkKey(0, 4, 0), hits.get(0).key);
        assertEquals(cell(3, 7, 5), hits.get(0).cell);
        assertEquals(eps, hits.get(0).w, 1e-9);
        // Each next stone cell down gets ε of what the one above passed: ε·(1−ε)^n.
        assertEquals(eps * (1 - eps), hits.get(1).w, 1e-9);
        assertEquals(cell(3, 6, 5), hits.get(1).cell);
        // The walk stops once the remaining transmission falls below TAU_MIN.
        double absorbed = hits.stream().mapToDouble(Hit::w).sum();
        assertTrue(1.0 - absorbed < SkyColumnPass.TAU_MIN, "all but < 1 ppm absorbed: " + absorbed);
        assertTrue(hits.size() < 8, "walk stops instead of visiting the whole column: " + hits.size());
    }

    @Test
    void everyCellColumnIsWalked(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        List<Hit> hits = walk(store);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
                assertEquals(cell(x, 7, z), at(hits, x, z).get(0).cell, "surface of (" + x + "," + z + ")");
    }

    @Test
    void leavesShadeTheGroundBelow(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, OAK_LEAVES, 285f);

        List<Hit> hits = at(walk(store), 0, 0);
        double leaves = mat(OAK_LEAVES).emissivity();
        double stone = mat(STONE).emissivity();
        assertEquals(cell(0, 8, 0), hits.get(0).cell);
        assertEquals(leaves, hits.get(0).w, 1e-9);
        // The canopy passes only (1 − ε_leaves) of the sky to the stone under it.
        assertEquals(cell(0, 7, 0), hits.get(1).cell);
        assertEquals((1 - leaves) * stone, hits.get(1).w, 1e-9);
    }

    @Test
    void anUnstoredColumnHasNoExposedCells(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        assertTrue(walk(store).isEmpty());
    }

    @Test
    void anAllAirColumnHasNoExposedCells(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        putSection(store, 5, sy -> AIR, 285f);
        assertTrue(walk(store).isEmpty());
    }

    @Test
    void aMixedCellVolumeMixesItsSlotsEmissivity(@TempDir Path dir) {
        SectionData d = SectionData.uniform(0f, 0f);
        setCell(d, 0, mat(DIRT), 1500f, 285f);
        d.setSlot(1, 0, WATER, 200f);
        double vDirt = 1500.0 / mat(DIRT).defaultMass();
        double vWater = 200.0 / mat(WATER).defaultMass();
        double expected = (vDirt * mat(DIRT).emissivity() + vWater * mat(WATER).emissivity()) / (vDirt + vWater);
        assertEquals(expected, SkyColumnPass.cellEmissivity(d, 0, lookup()), 1e-9);
        // One occupied slot is exactly the material's ε at any fill (v5 §4.8 parity).
        setCell(d, 1, mat(WATER), 300f, 285f);
        assertEquals(mat(WATER).emissivity(), SkyColumnPass.cellEmissivity(d, 1, lookup()), 1e-9);
        // An empty cell mixes to 0 — nothing to absorb.
        assertEquals(0.0, SkyColumnPass.cellEmissivity(d, 2, lookup()), 0.0);
    }
}
