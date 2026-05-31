package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link Material.State#AIR}.
 *
 * <p>{@code State.AIR} is the first-class air state: it is NON-fluid and NON-gas,
 * so {@code air()==true} while {@code fluid()==false} and {@code gas()==false}.
 * It is only ever set via the datapack {@code "state": "air"}; a material with no
 * {@code state} field still defaults to {@link Material.State#SOLID}.
 */
class MaterialAirStateTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "air");

    /** The bundled orge:air datapack material resolves to State.AIR (non-fluid, non-gas). */
    @Test
    void realResourceAirMaterialResolvesToAirState() throws Exception {
        String path = "/data/orge/orge/materials/air.json";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertNotNull(is, "Resource must exist: " + path);
            JsonElement body = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            Material m = MaterialCodec.fromJson(ID, body);

            assertEquals(Material.State.AIR, m.state(), "orge:air should resolve to State.AIR");
            assertTrue(m.air(), "air() should be true for State.AIR");
            assertFalse(m.fluid(), "State.AIR must be NON-fluid");
            assertFalse(m.gas(), "State.AIR must be NON-gas");
        }
    }

    /** A material with no {@code state} field still defaults to SOLID (regression guard). */
    @Test
    void absentStateDefaultsToSolid() {
        String json = """
                { "thermal_conductivity": 1.0, "heat_capacity": 500.0, "default_mass": 1000.0 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(Material.State.SOLID, m.state(), "absent state -> SOLID");
        assertFalse(m.air(), "SOLID material is not air");
        assertFalse(m.fluid(), "SOLID material is not fluid");
        assertFalse(m.gas(), "SOLID material is not gas");
    }
}
