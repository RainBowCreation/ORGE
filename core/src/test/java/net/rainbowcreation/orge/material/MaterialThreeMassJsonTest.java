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
        assertEquals(400f, l.minMass(), 1e-4f);
        assertEquals(3100f, l.maxMass(), 1e-4f);
    }

    @Test
    void steamFloorAndCapEqualToDefaultMass() throws Exception {
        Material s = load("steam");
        assertTrue(s.minMass() > 0f, "positive floor");
        assertEquals(0.6f, s.maxMass(), 1e-4f, "max_mass == default_mass this slice");
        assertEquals(0.6f, s.defaultMass(), 1e-4f);
        assertTrue(s.movable(), "steam JSON now carries a finite viscosity -> movable gas");
    }
}
