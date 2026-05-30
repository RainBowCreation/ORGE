package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MaterialBindingsPredicateTest {

    private static final Identifier CAMPFIRE = Identifier.fromNamespaceAndPath("minecraft", "campfire");
    private static final Identifier ORGE_CAMPFIRE = Identifier.fromNamespaceAndPath("orge", "campfire");
    private static final Identifier WIRE = Identifier.fromNamespaceAndPath("minecraft", "redstone_wire");
    private static final Identifier ORGE_PWR = Identifier.fromNamespaceAndPath("orge", "powered_redstone");

    private static final MaterialBindings.TagMembership NO_TAGS = (t, b) -> false;
    private static PropertyView view(Map<String, String> m) { return m::get; }

    @Test
    void predicatedOverrideMatchesOnlyWhenPredicateHolds() {
        MaterialBindings b = new MaterialBindings();
        b.addOverride(CAMPFIRE,
                List.of(new BlockStatePredicate.Requirement("lit", BlockStatePredicate.Op.EQ, "true")),
                ORGE_CAMPFIRE);
        assertEquals(ORGE_CAMPFIRE, b.materialFor(CAMPFIRE, view(Map.of("lit", "true")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID, b.materialFor(CAMPFIRE, view(Map.of("lit", "false")), NO_TAGS));
    }

    @Test
    void greaterThanOverrideForPower() {
        MaterialBindings b = new MaterialBindings();
        b.addOverride(WIRE,
                List.of(new BlockStatePredicate.Requirement("power", BlockStatePredicate.Op.GT, "0")),
                ORGE_PWR);
        assertEquals(ORGE_PWR, b.materialFor(WIRE, view(Map.of("power", "9")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID, b.materialFor(WIRE, view(Map.of("power", "0")), NO_TAGS));
    }

    @Test
    void plainOverloadStillWorksAndDelegatesToEmptyView() {
        MaterialBindings b = new MaterialBindings();
        Identifier water = Identifier.fromNamespaceAndPath("minecraft", "water");
        Identifier orgeWater = Identifier.fromNamespaceAndPath("orge", "water");
        b.addOverride(water, orgeWater);
        assertEquals(orgeWater, b.materialFor(water, NO_TAGS));
        assertEquals(orgeWater, b.materialFor(water, PropertyView.EMPTY, NO_TAGS));
    }

    @Test
    void predicatedTagBinding() {
        MaterialBindings b = new MaterialBindings();
        Identifier candles = Identifier.fromNamespaceAndPath("minecraft", "candles");
        Identifier whiteCandle = Identifier.fromNamespaceAndPath("minecraft", "white_candle");
        b.addTagBinding(candles,
                List.of(new BlockStatePredicate.Requirement("lit", BlockStatePredicate.Op.EQ, "true")),
                ORGE_CAMPFIRE);
        MaterialBindings.TagMembership inCandles = (t, blk) -> t.equals(candles);
        assertEquals(ORGE_CAMPFIRE, b.materialFor(whiteCandle, view(Map.of("lit", "true")), inCandles));
        assertEquals(MaterialRegistry.FALLBACK_ID, b.materialFor(whiteCandle, view(Map.of("lit", "false")), inCandles));
    }
}
