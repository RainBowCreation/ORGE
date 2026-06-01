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
 * The bundled {@code orge:air} material carries its compressible-gas DATA: the canonical {@code min_mass}
 * floor, the {@code max_mass} cap, {@code molar_mass}, and the resting {@code default_mass}. The old
 * {@code state}-based crash-guards are gone — air is a movable gas (finite viscosity).
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

            assertEquals(1.0f, air.minMass(), 1e-6f, "air's canonical relaxed floor (~1 kg, spec air-ish example)");
            assertEquals(1000f, air.maxMass(), 1e-4f, "air's 1000 kg compression cap (wide compressible band)");
            assertEquals(1.2f, air.defaultMass(), 1e-4f, "resting density unchanged");
            assertEquals(0.029f, air.molarMass(), 1e-6f, "molar mass preserved (threaded to engine)");
            assertTrue(air.movable(), "canonical air carries a finite viscosity -> movable gas");
        }
    }
}
