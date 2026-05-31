package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(w.fluid());
        assertEquals(125f, w.minFlowMass(), 1e-4f);
        assertEquals(1000f, w.maxMass(), 1e-4f, "max_mass == default_mass this slice");
        assertEquals(1000f, w.defaultMass(), 1e-4f);
        assertFalse(w.gas());
    }

    @Test
    void lavaHasFloorAndCapEqualToDefaultMass() throws Exception {
        Material l = load("lava");
        assertEquals(400f, l.minFlowMass(), 1e-4f);
        assertEquals(3100f, l.maxMass(), 1e-4f);
        assertFalse(l.gas());
    }

    @Test
    void steamIsGasWithPositiveFloorAndCapEqualToDefaultMass() throws Exception {
        Material s = load("steam");
        assertTrue(s.gas(), "steam is a tracked gas");
        assertTrue(s.fluid(), "a tracked gas participates in advection");
        assertTrue(s.minFlowMass() > 0f, "gas crash-guard: positive floor");
        assertEquals(0.6f, s.maxMass(), 1e-4f, "max_mass == default_mass this slice");
        assertEquals(0.6f, s.defaultMass(), 1e-4f);
    }
}
