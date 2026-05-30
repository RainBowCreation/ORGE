package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MaterialDataPredicateTest {

    private static final MaterialBindings.TagMembership NO_TAGS = (t, b) -> false;
    private static PropertyView view(Map<String, String> m) { return m::get; }

    @Test
    void overrideKeyWithPredicateResolvesConditionally() {
        String json = """
                { "overrides": { "minecraft:campfire[lit=true]": "orge:campfire" } }
                """;
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(JsonParser.parseString(json)), b);
        Identifier campfire = Identifier.fromNamespaceAndPath("minecraft", "campfire");
        assertEquals(Identifier.fromNamespaceAndPath("orge", "campfire"),
                b.materialFor(campfire, view(Map.of("lit", "true")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID,
                b.materialFor(campfire, view(Map.of("lit", "false")), NO_TAGS));
    }

    @Test
    void plainOverrideKeyStillWorks() {
        String json = """
                { "overrides": { "minecraft:water": "orge:water" } }
                """;
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(JsonParser.parseString(json)), b);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "water"),
                b.materialFor(Identifier.fromNamespaceAndPath("minecraft", "water"), NO_TAGS));
    }

    @Test
    void tagKeyWithPredicate() {
        String json = """
                { "tags": [ { "tag": "minecraft:candles[lit=true]", "material": "orge:candle" } ] }
                """;
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(JsonParser.parseString(json)), b);
        Identifier candles = Identifier.fromNamespaceAndPath("minecraft", "candles");
        Identifier whiteCandle = Identifier.fromNamespaceAndPath("minecraft", "white_candle");
        MaterialBindings.TagMembership inCandles = (t, blk) -> t.equals(candles);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "candle"),
                b.materialFor(whiteCandle, view(Map.of("lit", "true")), inCandles));
        assertEquals(MaterialRegistry.FALLBACK_ID,
                b.materialFor(whiteCandle, view(Map.of("lit", "false")), inCandles));
    }
}
