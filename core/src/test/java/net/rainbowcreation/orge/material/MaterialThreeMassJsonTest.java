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
 * now the single derived test ({@code movable() ⟺ viscosity finite}); the codec reads the legacy
 * {@code min_flow_mass} key into {@code min_mass} (best-effort until Task 1.3 renames it).
 *
 * <p>NOTE(Task 1.3): the bundled steam/air JSON still carry NO {@code viscosity} key, so they load as
 * frozen (immovable) under the canonical schema. Task 1.3 rewrites the JSON to add an explicit
 * viscosity, at which point these become movable again. These tests assert the current loaded truth.
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
    void waterHasFloorAndCapEqualToDefaultMass() throws Exception {
        Material w = load("water");
        assertTrue(w.movable(), "water JSON has viscosity -> movable");
        assertEquals(125f, w.minMass(), 1e-4f);
        assertEquals(1000f, w.maxMass(), 1e-4f, "max_mass == default_mass this slice");
        assertEquals(1000f, w.defaultMass(), 1e-4f);
    }

    @Test
    void lavaHasFloorAndCapEqualToDefaultMass() throws Exception {
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
        // TODO(Task 1.3): steam JSON lacks a viscosity key, so it loads frozen for now.
        assertFalse(s.movable(), "steam JSON has no viscosity yet -> frozen until Task 1.3");
    }
}
