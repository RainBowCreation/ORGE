package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HotSourceBindingsTest {

    private static JsonElement resource(String path) {
        try (InputStream in = HotSourceBindingsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static PropertyView view(Map<String, String> m) { return m::get; }
    private static final MaterialBindings.TagMembership NO_TAGS = (t, b) -> false;

    private static Material material(String id) {
        JsonElement body = resource("/data/orge/orge/materials/" + id + ".json");
        return MaterialCodec.fromJson(Identifier.fromNamespaceAndPath("orge", id), body);
    }
    private static MaterialBindings bindings() {
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(resource("/data/orge/orge/bindings/default.json")), b);
        return b;
    }

    @Test
    void lavaIsPinnedAt1400() {
        Material lava = material("lava");
        assertTrue(lava.pinned());
        assertEquals(1400f, lava.defaultTemperature(), 1e-3f);
    }

    @Test
    void fireBlockIdBinds() {
        Identifier fire = Identifier.fromNamespaceAndPath("minecraft", "fire");
        assertEquals(Identifier.fromNamespaceAndPath("orge", "fire"),
                bindings().materialFor(fire, PropertyView.EMPTY, NO_TAGS));
        assertTrue(material("fire").pinned());
    }

    @Test
    void litCampfireBindsButUnlitDoesNot() {
        Identifier campfire = Identifier.fromNamespaceAndPath("minecraft", "campfire");
        MaterialBindings b = bindings();
        assertEquals(Identifier.fromNamespaceAndPath("orge", "campfire"),
                b.materialFor(campfire, view(Map.of("lit", "true")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID,
                b.materialFor(campfire, view(Map.of("lit", "false")), NO_TAGS));
    }

    @Test
    void poweredRedstoneWireBindsViaGreaterThan() {
        Identifier wire = Identifier.fromNamespaceAndPath("minecraft", "redstone_wire");
        MaterialBindings b = bindings();
        assertEquals(Identifier.fromNamespaceAndPath("orge", "powered_redstone"),
                b.materialFor(wire, view(Map.of("power", "5")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID,
                b.materialFor(wire, view(Map.of("power", "0")), NO_TAGS));
    }
}
