package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
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
              "default_mass": 1000.0,
              "molar_mass": 0.018
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
}
