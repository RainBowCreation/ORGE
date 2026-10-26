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
    static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");
    static final Identifier UNKNOWN = Identifier.fromNamespaceAndPath("orge", "unobtainium");

    @BeforeEach
    void installWaterTable() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(TestMaterials.water()); // resolvable species, no latent plateau -> any T round-trips
        reg.put(TestMaterials.air());
        reg.put(TestMaterials.stone());
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

    /** The #50 repro: a drained waterlogged stair — slot 0 a trace of air, slot 1 the stairs' solid. */
    private static SectionData drainedStairCell(int cell, double enthalpyJ) {
        SectionData d = SectionData.uniform(0d, 0f);
        d.setMaterialAt(cell, AIR);
        d.setMass(cell, 0.0007f);
        d.setSlot(1, cell, STONE, 562.5f);
        d.setEnthalpy(cell, enthalpyJ);
        return d;
    }

    @Test
    void decodeCellDerivesTOnTheMixtureCurveNotSlotZero() {
        double e = (0.0007 * 1005.0 + 562.5 * 840.0) * 273.0; // E = Σ m_k·h_k(273) (both chain roots)
        SectionData d = drainedStairCell(9, e);
        assertEquals(273f, DerivedTemperature.decodeCell(d, 9), 0.01f,
                "T from E over the whole composition — slot 0's 0.0007 kg alone reads the 6000 K clamp");
    }

    @Test
    void encodeCellIsTheInverseOverTheWholeComposition() {
        SectionData d = SectionData.uniform(0d, 0f);
        d.setMaterialAt(4, STONE);
        d.setMass(4, 375f);
        d.setSlot(1, 4, WATER, 500f);
        double e = DerivedTemperature.encodeCell(290f, d, 4);
        assertEquals((375.0 * 840.0 + 500.0 * 4186.0) * 290.0, e, 1.0, "E = Σ m_k·h_k(T)");
        d.setEnthalpy(4, e);
        assertEquals(290f, DerivedTemperature.decodeCell(d, 4), 0.01f, "encodeCell -> decodeCell round-trips");
    }

    @Test
    void oneComponentCellDecodesAndEncodesAsBefore() {
        SectionData d = SectionData.uniform(0d, 0f);
        d.setMaterialAt(2, WATER);
        d.setMass(2, 1000f);
        d.setSlot(1, 2, UNKNOWN, 50f); // unresolved: carries no enthalpy curve
        float e = DerivedTemperature.encode(350f, 1000f, WATER);
        assertEquals(e, DerivedTemperature.encodeCell(350f, d, 2), 0d, "one resolvable component: same E");
        d.setEnthalpy(2, e);
        assertEquals(DerivedTemperature.decode(e, 1000f, WATER), DerivedTemperature.decodeCell(d, 2), 0f);
        SectionData empty = SectionData.uniform(0d, 0f);
        assertEquals(SectionData.DEFAULT_AMBIENT_K, DerivedTemperature.decodeCell(empty, 0), 0.001f,
                "no component -> ambient fallback");
        assertEquals(0d, DerivedTemperature.encodeCell(350f, empty, 0), 0d, "no component -> 0 J");
    }
}
