package net.rainbowcreation.orge.material;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BlockStatePredicateTest {

    private static PropertyView view(Map<String, String> m) { return m::get; }

    @Test
    void parsesPlainIdNoRequirements() {
        BlockStatePredicate.Parsed p = BlockStatePredicate.parseKey("minecraft:campfire");
        assertEquals("minecraft:campfire", p.id());
        assertTrue(p.requirements().isEmpty());
    }

    @Test
    void parsesEqualityPredicate() {
        BlockStatePredicate.Parsed p = BlockStatePredicate.parseKey("minecraft:campfire[lit=true]");
        assertEquals("minecraft:campfire", p.id());
        assertEquals(1, p.requirements().size());
        assertTrue(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("lit", "true"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("lit", "false"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of()))); // absent -> no match
    }

    @Test
    void parsesGreaterThanForIntegerPower() {
        BlockStatePredicate.Parsed p = BlockStatePredicate.parseKey("minecraft:redstone_wire[power>0]");
        assertTrue(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("power", "1"))));
        assertTrue(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("power", "15"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("power", "0"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("power", "x")))); // non-numeric
    }

    @Test
    void parsesMultipleRequirements() {
        BlockStatePredicate.Parsed p = BlockStatePredicate.parseKey("a:b[lit=true,power>3]");
        assertEquals(2, p.requirements().size());
        assertTrue(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("lit", "true", "power", "4"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("lit", "true", "power", "3"))));
    }
}
