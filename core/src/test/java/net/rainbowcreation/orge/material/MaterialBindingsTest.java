package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link MaterialBindings}.
 * Uses a lambda-based fake {@link MaterialBindings.TagMembership} backed by an
 * in-test map of tagId → set of blockIds so tests stay loader-agnostic.
 */
class MaterialBindingsTest {

    private MaterialBindings bindings;

    /** Fake tag membership: tagId → set of blockIds that belong to the tag. */
    private final Map<Identifier, Set<Identifier>> tagMembers = new HashMap<>();

    private final MaterialBindings.TagMembership fakeTags =
            (tagId, blockId) -> tagMembers.getOrDefault(tagId, Set.of()).contains(blockId);

    // Convenience helpers
    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static Identifier mc(String path)   { return id("minecraft", path); }
    private static Identifier orge(String path) { return id("orge", path); }
    private static Identifier ctag(String path) { return id("c", path); }

    @BeforeEach
    void setUp() {
        bindings = new MaterialBindings();
        tagMembers.clear();
    }

    // -------------------------------------------------------------------------
    // No bindings → fallback
    // -------------------------------------------------------------------------

    @Test
    void noBindingsReturnsFallback() {
        Identifier result = bindings.materialFor(mc("iron_block"), fakeTags);

        assertEquals(MaterialRegistry.FALLBACK_ID, result,
                "with no bindings, should return the global fallback");
    }

    // -------------------------------------------------------------------------
    // Override wins over tag and fallback
    // -------------------------------------------------------------------------

    @Test
    void overrideWinsOverMatchingTag() {
        Identifier ironBlock  = mc("iron_block");
        Identifier metalMat   = orge("metal");
        Identifier stoneMat   = orge("stone");
        Identifier stonesTag  = ctag("stones");

        // Both an override and a tag binding match the block
        bindings.addOverride(ironBlock, metalMat);
        bindings.addTagBinding(stonesTag, stoneMat);
        tagMembers.put(stonesTag, Set.of(ironBlock));  // iron_block is in #c:stones

        Identifier result = bindings.materialFor(ironBlock, fakeTags);

        assertEquals(metalMat, result,
                "per-block override should win over a matching tag binding");
    }

    @Test
    void overrideWinsOverFallback() {
        Identifier ironBlock = mc("iron_block");
        Identifier metalMat  = orge("metal");

        bindings.addOverride(ironBlock, metalMat);

        Identifier result = bindings.materialFor(ironBlock, fakeTags);

        assertEquals(metalMat, result,
                "per-block override should win over the global fallback");
    }

    // -------------------------------------------------------------------------
    // Tag binding wins over fallback
    // -------------------------------------------------------------------------

    @Test
    void tagBindingWinsOverFallback() {
        Identifier ironBlock = mc("iron_block");
        Identifier metalMat  = orge("metal");
        Identifier metalsTag = ctag("metals");

        bindings.addTagBinding(metalsTag, metalMat);
        tagMembers.put(metalsTag, Set.of(ironBlock));

        Identifier result = bindings.materialFor(ironBlock, fakeTags);

        assertEquals(metalMat, result,
                "matching tag binding should win over the global fallback");
    }

    @Test
    void tagBindingNotHitWhenBlockNotInTag() {
        Identifier ironBlock  = mc("iron_block");
        Identifier gravelBlock = mc("gravel");
        Identifier metalMat  = orge("metal");
        Identifier metalsTag = ctag("metals");

        bindings.addTagBinding(metalsTag, metalMat);
        tagMembers.put(metalsTag, Set.of(ironBlock));  // gravel is NOT in #c:metals

        Identifier result = bindings.materialFor(gravelBlock, fakeTags);

        assertEquals(MaterialRegistry.FALLBACK_ID, result,
                "should fall back when block is not in any bound tag");
    }

    // -------------------------------------------------------------------------
    // First-registered tag wins when multiple tags match
    // -------------------------------------------------------------------------

    @Test
    void firstRegisteredTagWinsWhenMultipleTagsMatch() {
        Identifier ironBlock = mc("iron_block");
        Identifier metalMat  = orge("metal");
        Identifier stoneMat  = orge("stone");
        Identifier metalsTag = ctag("metals");
        Identifier stonesTag = ctag("stones");

        // Register metals BEFORE stones — metals should win
        bindings.addTagBinding(metalsTag, metalMat);
        bindings.addTagBinding(stonesTag, stoneMat);

        // iron_block is in BOTH tags
        tagMembers.put(metalsTag, Set.of(ironBlock));
        tagMembers.put(stonesTag, Set.of(ironBlock));

        Identifier result = bindings.materialFor(ironBlock, fakeTags);

        assertEquals(metalMat, result,
                "the first-registered tag should win when multiple tags match the same block");
    }

    /**
     * Negative complement: confirms the test above would FAIL if tag order were reversed.
     * Registers stones BEFORE metals; now stones should win.
     */
    @Test
    void secondRegisteredTagLosesWhenFirstAlsoMatches() {
        Identifier ironBlock = mc("iron_block");
        Identifier metalMat  = orge("metal");
        Identifier stoneMat  = orge("stone");
        Identifier metalsTag = ctag("metals");
        Identifier stonesTag = ctag("stones");

        // Register stones BEFORE metals this time
        bindings.addTagBinding(stonesTag, stoneMat);
        bindings.addTagBinding(metalsTag, metalMat);

        tagMembers.put(metalsTag, Set.of(ironBlock));
        tagMembers.put(stonesTag, Set.of(ironBlock));

        Identifier result = bindings.materialFor(ironBlock, fakeTags);

        assertEquals(stoneMat, result,
                "when stones tag is registered first, it should win over metals tag");
    }

    // -------------------------------------------------------------------------
    // clear()
    // -------------------------------------------------------------------------

    @Test
    void clearRemovesOverrides() {
        Identifier ironBlock = mc("iron_block");
        Identifier metalMat  = orge("metal");

        bindings.addOverride(ironBlock, metalMat);
        bindings.clear();

        Identifier result = bindings.materialFor(ironBlock, fakeTags);

        assertEquals(MaterialRegistry.FALLBACK_ID, result,
                "after clear(), override should be gone and fallback returned");
    }

    @Test
    void clearRemovesTagBindings() {
        Identifier ironBlock = mc("iron_block");
        Identifier metalMat  = orge("metal");
        Identifier metalsTag = ctag("metals");

        bindings.addTagBinding(metalsTag, metalMat);
        tagMembers.put(metalsTag, Set.of(ironBlock));
        bindings.clear();

        Identifier result = bindings.materialFor(ironBlock, fakeTags);

        assertEquals(MaterialRegistry.FALLBACK_ID, result,
                "after clear(), tag bindings should be gone and fallback returned");
    }

    @Test
    void clearThenRebindWorks() {
        Identifier ironBlock = mc("iron_block");
        Identifier metalMat  = orge("metal");

        bindings.addOverride(ironBlock, orge("old_metal"));
        bindings.clear();
        bindings.addOverride(ironBlock, metalMat);

        Identifier result = bindings.materialFor(ironBlock, fakeTags);

        assertEquals(metalMat, result,
                "bindings added after clear() should take effect");
    }
}
