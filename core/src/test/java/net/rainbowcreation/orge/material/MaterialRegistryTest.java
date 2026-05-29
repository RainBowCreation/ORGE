package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link MaterialRegistry}.
 */
class MaterialRegistryTest {

    private MaterialRegistry registry;

    /** Convenience: build a minimal {@link Material} with the given id. */
    private static Material mat(String namespace, String path) {
        return new Material(
                Identifier.fromNamespaceAndPath(namespace, path),
                2.0f,   // thermalConductivity
                840f,   // heatCapacity
                0f,     // viscosity
                2500f,  // defaultMass
                0.06f,  // molarMass
                Float.POSITIVE_INFINITY,  // boilingPoint
                Float.NEGATIVE_INFINITY,  // freezingPoint
                null,   // boilingTarget
                null,   // freezingTarget
                null    // representativeBlock
        );
    }

    private static Material fallbackMaterial() {
        return new Material(
                MaterialRegistry.FALLBACK_ID,
                2.0f,
                840f,
                0f,
                2500f,
                0.06f,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                null,
                null,
                null
        );
    }

    @BeforeEach
    void setUp() {
        registry = new MaterialRegistry();
    }

    // -------------------------------------------------------------------------
    // put + get
    // -------------------------------------------------------------------------

    @Test
    void putThenGetReturnsTheMaterial() {
        Material stone = mat("orge", "stone");
        registry.put(stone);

        Optional<Material> result = registry.get(Identifier.fromNamespaceAndPath("orge", "stone"));

        assertTrue(result.isPresent(), "get should return present after put");
        assertSame(stone, result.get(), "get should return the same material instance");
    }

    @Test
    void getUnknownIdReturnsEmpty() {
        Optional<Material> result = registry.get(Identifier.fromNamespaceAndPath("orge", "unknown"));

        assertTrue(result.isEmpty(), "get of unregistered id should return Optional.empty()");
    }

    @Test
    void putReplacesExistingEntry() {
        Material first  = mat("orge", "stone");
        Material second = mat("orge", "stone");
        registry.put(first);
        registry.put(second);

        Optional<Material> result = registry.get(Identifier.fromNamespaceAndPath("orge", "stone"));

        assertTrue(result.isPresent());
        assertSame(second, result.get(), "second put should replace the first");
    }

    // -------------------------------------------------------------------------
    // getOrFallback — fallback present, id absent
    // -------------------------------------------------------------------------

    @Test
    void getOrFallbackReturnsFallbackWhenIdAbsent() {
        Material fallback = fallbackMaterial();
        registry.put(fallback);

        Material result = registry.getOrFallback(Identifier.fromNamespaceAndPath("orge", "nonexistent"));

        assertSame(fallback, result, "should return the fallback material when requested id is absent");
    }

    // -------------------------------------------------------------------------
    // getOrFallback — neither id nor fallback registered → IllegalStateException
    // -------------------------------------------------------------------------

    @Test
    void getOrFallbackThrowsWhenNeitherIdNorFallbackRegistered() {
        assertThrows(
                IllegalStateException.class,
                () -> registry.getOrFallback(Identifier.fromNamespaceAndPath("orge", "missing")),
                "should throw IllegalStateException when neither id nor fallback is registered"
        );
    }

    // -------------------------------------------------------------------------
    // getOrFallback — id IS present: returns exact material, does NOT fall back
    // -------------------------------------------------------------------------

    @Test
    void getOrFallbackReturnsMaterialWhenIdPresent() {
        Material stone    = mat("orge", "stone");
        Material fallback = fallbackMaterial();
        registry.put(fallback);
        registry.put(stone);

        Material result = registry.getOrFallback(Identifier.fromNamespaceAndPath("orge", "stone"));

        assertSame(stone, result, "should return the registered material, not the fallback");
    }

    // -------------------------------------------------------------------------
    // clear
    // -------------------------------------------------------------------------

    @Test
    void clearEmptiesTheRegistry() {
        registry.put(mat("orge", "stone"));
        registry.put(fallbackMaterial());

        registry.clear();

        assertTrue(registry.all().isEmpty(), "all() should be empty after clear()");
        assertTrue(
                registry.get(Identifier.fromNamespaceAndPath("orge", "stone")).isEmpty(),
                "get should return empty after clear()"
        );
    }
}
