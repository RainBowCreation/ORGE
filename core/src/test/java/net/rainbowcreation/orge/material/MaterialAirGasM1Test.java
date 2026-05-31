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
 * §11 Phase A, Task M1: air gains its compressible-gas DATA (data + crash-guard only,
 * zero behaviour change — the {@code fluid()}/{@code gas()} flag flip is staged later in
 * M3/E1/INT, not here).
 *
 * <p>The reloaded {@code orge:air} datapack material must now carry the compressible
 * range ({@code min_flow_mass=0.001}, {@code max_mass=1000}), keep its {@code molar_mass=0.029}
 * and resting {@code default_mass=1.2}, and resolve to {@code State.AIR}. The crash-guard must
 * reject a {@code state=air} material defined with no positive {@code min_flow_mass}.</p>
 */
class MaterialAirGasM1Test {

    private static final Identifier AIR_ID = Identifier.fromNamespaceAndPath("orge", "air");

    /** The bundled orge:air material: compressible range + molar mass (data only). */
    @Test
    void bundledAirHasCompressibleRangeAndMolarMass() throws Exception {
        String path = "/data/orge/orge/materials/air.json";
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            JsonElement body = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Material air = MaterialCodec.fromJson(AIR_ID, body);

            assertEquals(Material.State.AIR, air.state(), "orge:air resolves to State.AIR");
            assertEquals(0.001f, air.minFlowMass(), 1e-6f, "air gains a 1 g flow floor");
            assertEquals(1000f, air.maxMass(), 1e-4f, "air gains a 1000 kg compression cap");
            assertEquals(1.2f, air.defaultMass(), 1e-4f, "resting density unchanged");
            assertEquals(0.029f, air.molarMass(), 1e-6f, "molar mass preserved (threaded to engine)");
        }
    }

    /** Crash-guard: a state=air material with no positive min_flow_mass is rejected. */
    @Test
    void airWithNoFlowFloorIsRejected() {
        String json = """
                {
                  "thermal_conductivity": 0.026,
                  "heat_capacity": 1005,
                  "default_mass": 1.2,
                  "molar_mass": 0.029,
                  "max_mass": 1000,
                  "state": "air"
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MaterialCodec.fromJson(AIR_ID, JsonParser.parseString(json)),
                "state=air with no min_flow_mass must be rejected by the crash-guard");
        assertTrue(ex.getMessage() == null || ex.getMessage().contains("min_flow_mass")
                        || ex.getMessage().contains("state=air") || ex.getMessage().contains("density"),
                "guard message should mention the floor requirement: " + ex.getMessage());
    }

    /** The existing gas crash-guard for state=gas must remain intact. */
    @Test
    void gasWithNoFlowFloorStillRejected() {
        String json = """
                {
                  "thermal_conductivity": 0.02,
                  "heat_capacity": 2000,
                  "default_mass": 0.6,
                  "state": "gas"
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> MaterialCodec.fromJson(AIR_ID, JsonParser.parseString(json)),
                "state=gas with no min_flow_mass must still be rejected");
    }
}
