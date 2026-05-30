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

class ColdSourceBindingsTest {

    private static JsonElement resource(String path) {
        try (InputStream in = ColdSourceBindingsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static PropertyView view(Map<String, String> m) { return m::get; }
    private static final MaterialBindings.TagMembership NO_TAGS = (t, b) -> false;

    private static Material material(String id) {
        return MaterialCodec.fromJson(Identifier.fromNamespaceAndPath("orge", id),
                resource("/data/orge/orge/materials/" + id + ".json"));
    }
    private static MaterialBindings bindings() {
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(resource("/data/orge/orge/bindings/default.json")), b);
        return b;
    }

    @Test
    void blueIcePinnedCold() {
        Material m = material("blue_ice");
        assertTrue(m.pinned());
        assertEquals(250f, m.defaultTemperature(), 1e-3f);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "blue_ice"),
                bindings().materialFor(Identifier.fromNamespaceAndPath("minecraft", "blue_ice"),
                        PropertyView.EMPTY, NO_TAGS));
    }

    @Test
    void endRodAndSoulFireBind() {
        MaterialBindings b = bindings();
        assertEquals(Identifier.fromNamespaceAndPath("orge", "end_rod"),
                b.materialFor(Identifier.fromNamespaceAndPath("minecraft", "end_rod"), PropertyView.EMPTY, NO_TAGS));
        assertEquals(Identifier.fromNamespaceAndPath("orge", "soul_fire"),
                b.materialFor(Identifier.fromNamespaceAndPath("minecraft", "soul_fire"), PropertyView.EMPTY, NO_TAGS));
    }

    @Test
    void litSoulCampfireBinds() {
        Identifier sc = Identifier.fromNamespaceAndPath("minecraft", "soul_campfire");
        assertEquals(Identifier.fromNamespaceAndPath("orge", "soul_campfire"),
                bindings().materialFor(sc, view(Map.of("lit", "true")), NO_TAGS));
    }
}
