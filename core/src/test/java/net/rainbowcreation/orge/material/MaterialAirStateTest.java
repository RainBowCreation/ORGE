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
 * The canonical schema removes the {@code State} enum and the air/fluid/gas/solid distinction:
 * movability is the single derived test ({@code movable() ⟺ viscosity finite}). What was the
 * "first-class air state" is now just a material; the bundled orge:air still loads, carrying its
 * canonical mass/molar data.
 *
 * <p>The bundled air JSON carries a small finite {@code viscosity}, so air is a movable gas.</p>
 */
class MaterialAirStateTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "air");

    /** The bundled orge:air datapack material loads with its canonical mass data. */
    @Test
    void realResourceAirMaterialLoads() throws Exception {
        String path = "/data/orge/orge/materials/air.json";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertNotNull(is, "Resource must exist: " + path);
            JsonElement body = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            Material m = MaterialCodec.fromJson(ID, body);

            assertEquals(1.2f, m.defaultMass(), 1e-4f, "resting density");
            assertEquals(1.0f, m.minMass(), 1e-6f, "air's canonical relaxed floor (~1 kg)");
            assertEquals(1000f, m.maxMass(), 1e-4f, "air's compression cap");
            assertTrue(m.movable(), "canonical air carries a finite viscosity -> movable gas");
        }
    }

    /** A material with no viscosity key loads frozen (absent viscosity -> +INF). */
    @Test
    void absentViscosityIsFrozen() {
        String json = """
                { "thermal_conductivity": 1.0, "heat_capacity": 500.0, "molar_mass": 0.018,
                  "default_mass": 1000.0, "default_temperature": 290 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertFalse(m.movable(), "absent viscosity -> frozen (immovable)");
    }
}
