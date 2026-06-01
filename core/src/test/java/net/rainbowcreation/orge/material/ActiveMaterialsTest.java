package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the atomic state swap in {@link ActiveMaterials} — the unit-testable
 * half of the reload listener (no {@link net.minecraft.server.packs.resources.ResourceManager}).
 */
class ActiveMaterialsTest {

    @BeforeEach
    void reset() {
        ActiveMaterials.swap(new ActiveMaterials.State(new MaterialRegistry(), new MaterialBindings()));
    }

    private static Identifier id(String full) {
        return Identifier.parse(full);
    }

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    private static final String VALID_MATERIAL = """
            {
              "thermal_conductivity": 0.6,
              "heat_capacity": 4186.0,
              "molar_mass": 0.018,
              "default_mass": 1000.0,
              "default_temperature": 290.0
            }
            """;

    private static Map<Identifier, JsonElement> oneMaterial(String matId) {
        Map<Identifier, JsonElement> m = new HashMap<>();
        m.put(id(matId), json(VALID_MATERIAL));
        return m;
    }

    @Test
    void buildState_doesNotTouchActiveState() {
        ActiveMaterials.State before = ActiveMaterials.current();
        ActiveMaterials.buildState(oneMaterial("orge:water"), List.of());
        // buildState builds fresh instances only; the active reference is unchanged.
        assertSame(before, ActiveMaterials.current(),
                "buildState must not publish anything to the active holder");
    }

    @Test
    void reloadFrom_goodData_swapsIn() {
        ActiveMaterials.reloadFrom(oneMaterial("orge:water"), List.of());

        assertTrue(ActiveMaterials.registry().get(id("orge:water")).isPresent(),
                "a successful reload makes the new material queryable");
    }

    @Test
    void reloadFrom_badData_leavesActiveStateUnchanged() {
        // First, install a known-good state.
        ActiveMaterials.reloadFrom(oneMaterial("orge:water"), List.of());
        ActiveMaterials.State good = ActiveMaterials.current();

        // Now attempt a reload that MaterialData will reject (malformed material body).
        Map<Identifier, JsonElement> broken = new HashMap<>();
        broken.put(id("orge:broken"), json("\"not-an-object\""));

        assertThrows(IllegalArgumentException.class,
                () -> ActiveMaterials.reloadFrom(broken, List.of()));

        // Atomicity: the failed reload must leave the previously-active state in place.
        assertSame(good, ActiveMaterials.current(),
                "a failed reload must NOT corrupt or replace the active state");
        assertTrue(ActiveMaterials.registry().get(id("orge:water")).isPresent(),
                "the previously-loaded material is still active after a failed reload");
        assertTrue(ActiveMaterials.registry().get(id("orge:broken")).isEmpty(),
                "the rejected material must not leak into the active registry");
    }

    @Test
    void buildState_loadsBindings() {
        String bindingsJson = """
                {
                  "overrides": { "minecraft:iron_block": "orge:iron" }
                }
                """;
        ActiveMaterials.State state = ActiveMaterials.buildState(
                oneMaterial("orge:water"), List.of(json(bindingsJson)));

        // Bindings are stored (override resolution does not need live tags).
        assertEquals(id("orge:iron"),
                state.bindings().materialFor(id("minecraft:iron_block"), (t, b) -> false));
    }

    @Test
    void swap_rejectsNull() {
        assertThrows(NullPointerException.class, () -> ActiveMaterials.swap(null));
    }

    /**
     * Documents the initial-state contract: {@link ActiveMaterials#current()} is never
     * null, {@code registry().get(id)} returns empty, but {@code registry().getOrFallback(id)}
     * throws until the first SERVER_DATA reload populates {@code orge:generic_solid}.
     */
    @Test
    void initialState_registryEmptyAndGetOrFallbackThrows() {
        // After @BeforeEach reset the state is a fresh empty registry + bindings.
        ActiveMaterials.State state = ActiveMaterials.current();
        assertNotNull(state, "current() must never return null");
        assertNotNull(state.registry(), "registry() must never return null");
        assertNotNull(state.bindings(), "bindings() must never return null");

        // get() on an empty registry returns empty — no exception.
        assertTrue(ActiveMaterials.registry().get(id("orge:water")).isEmpty(),
                "get() must return empty on an unpopulated registry");

        // getOrFallback() throws because orge:generic_solid has not been loaded yet.
        assertThrows(IllegalStateException.class,
                () -> ActiveMaterials.registry().getOrFallback(id("orge:water")),
                "getOrFallback() must throw before the fallback material is registered");
    }
}
