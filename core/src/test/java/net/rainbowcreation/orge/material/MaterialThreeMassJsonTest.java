package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Floor/cap ({@code min_mass}/{@code max_mass}) loaded from the bundled datapack JSON. Movability is
 * now the single derived test ({@code movable() ⟺ viscosity finite}); the codec reads the canonical
 * {@code min_mass} key. The bundled water/lava/steam JSON all carry a finite viscosity -> movable.
 */
class MaterialThreeMassJsonTest {

    private static Material load(String name) throws Exception {
        String path = "/data/orge/orge/materials/" + name + ".json";
        try (InputStream in = MaterialThreeMassJsonTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            JsonElement body = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return MaterialCodec.fromJson(Identifier.fromNamespaceAndPath("orge", name), body);
        }
    }

    @Test
    void waterHasFloorAndCap() throws Exception {
        Material w = load("water");
        assertTrue(w.movable(), "water JSON has viscosity -> movable");
        assertEquals(125f, w.minMass(), 1e-4f);
        // Headroom calibration was REVERTED with the audit-#1 §D.1 relabel (it fabricated water by
        // eating air). Liquids are back to max_mass == default_mass until Stage-2 conservative
        // displacement + §G.2 calibration land together.
        assertEquals(1000f, w.maxMass(), 1e-4f);
        assertEquals(1000f, w.defaultMass(), 1e-4f);
    }

    @Test
    void lavaHasFloorAndCap() throws Exception {
        Material l = load("lava");
        assertTrue(l.movable(), "lava JSON has viscosity -> movable");
        // v4 §1.2 / engine_b_real_lut.hpp line 38: lava 330/2650/2650 (basaltic melt,
        // lighter than its 2700 stone solid). Migrated from the old 400/3100 placeholders.
        assertEquals(330f, l.minMass(), 1e-4f);
        assertEquals(2650f, l.maxMass(), 1e-4f);
    }

    @Test
    void steamFloorAndCapEqualToDefaultMass() throws Exception {
        Material s = load("steam");
        assertTrue(s.minMass() > 0f, "positive floor");
        // v4 §1.2 / engine_b_real_lut.hpp line 44: steam has a REAL gas band 0.06/0.6/1000
        // (a boiled 1000 kg water cell is in-band). max_mass is now 1000, not default_mass.
        assertEquals(1000f, s.maxMass(), 1e-4f, "steam compression cap (wide gas band)");
        assertEquals(0.6f, s.defaultMass(), 1e-4f);
        assertTrue(s.movable(), "steam JSON now carries a finite viscosity -> movable gas");
    }
}
