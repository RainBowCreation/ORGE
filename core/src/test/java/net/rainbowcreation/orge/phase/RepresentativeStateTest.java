package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RepresentativeState} — the vanilla state a multi-component cell is drawn as (DESIGN-LAW
 * v5 #7, proposal §6). Engine slots come back in LUT order, not by role, so the water or air a stair or
 * rail holds can be slot 0: the host is the live block, kept while it is among the components; only its
 * {@code waterlogged} property follows its water (hysteresis 0.50 on / 0.25 off of the open volume).
 */
class RepresentativeStateTest {

    private static Identifier mc(String path) { return Identifier.fromNamespaceAndPath("minecraft", path); }
    private static Identifier orge(String path) { return Identifier.fromNamespaceAndPath("orge", path); }

    /** The real stone_stairs row: 2025 kg of stone (ρ_p 2700) ⇒ 0.25 m³ open, 250 kg of water saturated. */
    private static Material stairs() {
        return Material.builder(orge("stone_stairs"))
                .thermalConductivity(2.5f).heatCapacity(800f).molarMass(0.065f)
                .defaultMass(2025f).minMass(2025f).maxMass(2025f).defaultTemperature(290f)
                .viscosity(1e21f).yieldStress(1.4e8f)
                .particleDensity(2700f).flowResistance(192f)
                .build();
    }

    /** The real rail row: 157.48 kg of iron (ρ_p 7874), μ = +∞. */
    private static Material rail() {
        return Material.builder(orge("rail"))
                .thermalConductivity(80f).heatCapacity(449f).molarMass(0.0558f)
                .defaultMass(157.48f).defaultTemperature(290f)
                .particleDensity(7874f).flowResistance(12.245f)
                .build();
    }

    /** A full block on the fallback material: no open volume (ρ_p = default_mass). */
    private static Material solid() {
        return Material.builder(orge("generic_solid"))
                .thermalConductivity(2f).heatCapacity(840f).molarMass(0.06f)
                .defaultMass(2500f).defaultTemperature(290f).viscosity(1e21f).yieldStress(1.4e8f)
                .representativeBlock(mc("stone"))
                .build();
    }

    private static Material water() {
        return Material.builder(orge("water"))
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).minMass(125f).maxMass(1000f).defaultTemperature(290f).viscosity(1e-3f)
                .representativeBlock(mc("water"))
                .build();
    }

    private static Material air() {
        return Material.builder(orge("air"))
                .thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.029f)
                .defaultMass(1.2f).minMass(1f).maxMass(1000f).defaultTemperature(290f).viscosity(1.8e-5f)
                .representativeBlock(mc("air"))
                .build();
    }

    private static RepresentativeState.Component c(Material m, float kg) {
        return new RepresentativeState.Component(m, kg);
    }

    @Test
    void saturatedStairIsDrawnWaterloggedAndKept() {
        // LUT order puts orge:stone_stairs before orge:water, but nothing depends on slot order.
        List<RepresentativeState.Component> comps = List.of(c(water(), 250f), c(stairs(), 2025f));
        assertTrue(RepresentativeState.hostPresent(stairs(), comps), "the stairs are still there: keep the block");
        assertEquals(Boolean.TRUE, RepresentativeState.waterlogged(stairs(), comps, false));
    }

    @Test
    void waterloggedFollowsThePoreFillWithHysteresis() {
        Material s = stairs();
        // 0.25 m³ open: 125 kg = 0.50 (on), 62.5 kg = 0.25 (off edge).
        assertEquals(Boolean.TRUE,  RepresentativeState.waterlogged(s, List.of(c(s, 2025f), c(water(), 125f)), false),
                "at half the open volume the block turns waterlogged");
        assertEquals(Boolean.FALSE, RepresentativeState.waterlogged(s, List.of(c(s, 2025f), c(water(), 100f)), false),
                "inside the band a dry block stays dry");
        assertEquals(Boolean.TRUE,  RepresentativeState.waterlogged(s, List.of(c(s, 2025f), c(water(), 100f)), true),
                "inside the band a waterlogged block stays waterlogged");
        assertEquals(Boolean.TRUE,  RepresentativeState.waterlogged(s, List.of(c(s, 2025f), c(water(), 62.5f)), true),
                "at a quarter it is still waterlogged");
        assertEquals(Boolean.FALSE, RepresentativeState.waterlogged(s, List.of(c(s, 2025f), c(water(), 50f)), true),
                "below a quarter it turns dry");
    }

    @Test
    void drainingTheWaterNeverRemovesTheHost() {
        Material s = stairs();
        // fully drained: the cell is one-component stairs again — kept, drawn dry.
        List<RepresentativeState.Component> dry = List.of(c(s, 2025f));
        assertTrue(RepresentativeState.hostPresent(s, dry));
        assertEquals(Boolean.FALSE, RepresentativeState.waterlogged(s, dry, true));
        // a trace of water left: still the stairs, never a water block.
        List<RepresentativeState.Component> trace = List.of(c(water(), 0.5f), c(s, 2025f));
        assertTrue(RepresentativeState.hostPresent(s, trace));
        assertEquals(Boolean.FALSE, RepresentativeState.waterlogged(s, trace, true));
    }

    @Test
    void aRailHoldingAirAndWaterIsKeptAsTheRail() {
        // orge:air sorts before orge:rail, so air is slot 0 — the rail is still the host.
        List<RepresentativeState.Component> comps = List.of(c(air(), 1.18f), c(rail(), 157.48f), c(water(), 300f));
        assertTrue(RepresentativeState.hostPresent(rail(), comps), "the rail stays drawn");
    }

    @Test
    void aHostWithNoOpenVolumeKeepsItsWaterStateAsItIs() {
        Material s = solid();
        assertNull(RepresentativeState.waterlogged(s, List.of(c(s, 2500f)), true),
                "a full block on the fallback material holds no water: leave the property alone");
    }

    @Test
    void hostAbsentIsDrawnAsItsLargestCondensedComponent() {
        // the live block is stairs but the engine holds only water (host gone): not kept.
        List<RepresentativeState.Component> water = List.of(c(water(), 600f));
        assertFalse(RepresentativeState.hostPresent(stairs(), water));
        assertEquals("orge:water", RepresentativeState.dominant(water).material().id().toString());
        // an air cell holding rain: air is listed first and has more mass than nothing, but its condensed
        // volume is ≈ 0 — the cell is drawn as the water.
        List<RepresentativeState.Component> rain = List.of(c(air(), 1.2f), c(water(), 40f));
        assertFalse(RepresentativeState.hostPresent(air(), rain), "air is never a kept host (it is a fluid)");
        RepresentativeState.Component dom = RepresentativeState.dominant(rain);
        assertEquals("orge:water", dom.material().id().toString());
        assertEquals(40f, dom.massKg(), 0f);
        // one component: that component (today's behaviour).
        assertEquals("orge:air", RepresentativeState.dominant(List.of(c(air(), 1.2f))).material().id().toString());
        assertNull(RepresentativeState.dominant(List.of()), "an empty cell has no component to draw");
    }

    @Test
    void aFluidLiveBlockIsNeverAKeptHost() {
        List<RepresentativeState.Component> comps = List.of(c(water(), 1000f));
        assertFalse(RepresentativeState.hostPresent(water(), comps), "water is drawn by the level path");
        assertNull(RepresentativeState.waterlogged(water(), comps, false));
    }
}
