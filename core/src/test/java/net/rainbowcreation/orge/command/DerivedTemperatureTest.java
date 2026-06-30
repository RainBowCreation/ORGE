package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The temperature&lt;-&gt;enthalpy seam in isolation (Law §6/§7): {@code encode} turns a kelvin edit into
 * stored extensive E, {@code decode} re-derives kelvin from stored E + mass. A resolvable species with
 * mass round-trips; a massless/unresolved cell carries no enthalpy and reads the ambient fallback; an
 * out-of-range stored E is clamped at the {@code [0,6000]} derive boundary.
 */
class DerivedTemperatureTest {

    static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    static final Identifier UNKNOWN = Identifier.fromNamespaceAndPath("orge", "unobtainium");

    @BeforeEach
    void installWaterTable() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(TestMaterials.water()); // resolvable species, no latent plateau -> any T round-trips
        ActiveMaterials.swap(new ActiveMaterials.State(reg));
    }

    @AfterEach
    void resetTable() {
        ActiveMaterials.swap(new ActiveMaterials.State(new MaterialRegistry()));
    }

    @Test
    void encodeThenDecodeRoundTripsKelvin() {
        float e = DerivedTemperature.encode(350f, 1000f, WATER);
        assertTrue(e > 0f, "a water cell with mass stores real enthalpy");
        assertEquals(350f, DerivedTemperature.decode(e, 1000f, WATER), 0.05f,
                "encode(kelvin)->E then decode(E)->kelvin round-trips off-plateau");
    }

    @Test
    void masslessOrUnresolvedCarriesNoEnthalpyAndDecodesAmbient() {
        assertEquals(0f, DerivedTemperature.encode(350f, 0f, WATER), 0f, "massless cell encodes 0 J");
        assertEquals(0f, DerivedTemperature.encode(350f, 1000f, UNKNOWN), 0f, "unresolved species encodes 0 J");
        assertEquals(SectionData.DEFAULT_AMBIENT_K, DerivedTemperature.decode(0d, 0f, WATER), 0.001f,
                "massless cell derives the ambient fallback");
        assertEquals(SectionData.DEFAULT_AMBIENT_K, DerivedTemperature.decode(1e6d, 1000f, UNKNOWN), 0.001f,
                "unresolved species derives the ambient fallback");
    }

    @Test
    void decodeClampsCorruptHugeEToDeriveBoundary() {
        assertEquals(6000f, DerivedTemperature.decode(1e12d, 1f, WATER), 0.001f,
                "an absurd stored E shows the clamped 6000 K derive boundary, not a runaway T");
    }
}
