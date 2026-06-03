package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EngineOutSpecies} — the pure resolver that pairs a cell with the species the
 * native engine says it BECAME this step ({@code matOut}), not the stale pre-swap live block.
 *
 * <p>Regression for the vertical molar-sort phase bug: lava placed on water makes the engine swap
 * lava↓/water↑. After the swap the top cell carries water's cool temperature, but the live block is
 * still lava (the reconciler rewrites blocks only AFTER the phase changer). Sourcing the cell's
 * material from the live block paired it with the swapped-in cool temperature, so {@code PhaseRule}
 * read {@code cool < lava.minTemp} and froze the risen water into {@code lava.minTarget} = stone.
 * The fix sources material from {@code matOut}, so the top cell reads as water (no transition).</p>
 */
class EngineOutSpeciesTest {

    private static Identifier mat(String path) { return Identifier.fromNamespaceAndPath("orge", path); }

    private static Material water() {
        return Material.builder(mat("water"))
                .thermalConductivity(0.6f).heatCapacity(1000f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(293f).viscosity(0f)
                .maxTemp(373.15f).minTemp(273.15f)
                .maxTarget(mat("steam")).minTarget(mat("ice"))
                .build();
    }

    private static Material lava() {
        return Material.builder(mat("lava"))
                .thermalConductivity(1.0f).heatCapacity(1000f).molarMass(0.30f)
                .defaultMass(3000f).defaultTemperature(1500f).viscosity(0.9f)
                .minTemp(1000f).minTarget(mat("stone"))
                .build();
    }

    private static final Material VOID = Material.builder(mat("vacuum"))
            .thermalConductivity(0f).heatCapacity(1f).molarMass(0f)
            .defaultMass(0f).defaultTemperature(0f).viscosity(0f).build();

    /** The bug: a swapped-up cell whose live block is still lava but whose engine species is water. */
    @Test
    void prefersEngineSpeciesOverStaleLiveBlock() {
        List<Material> lut = List.of(VOID, water(), lava());
        char[] outMat = {1 /* water */};
        Material resolved = EngineOutSpecies.resolve(outMat, lut, 0, /* live= */ lava());
        assertEquals(mat("water"), resolved.id(),
                "phase must read the post-swap engine species (water), not the stale lava block");
    }

    @Test
    void fallsBackToLiveWhenNoEngineSpecies() {
        assertEquals(mat("lava"), EngineOutSpecies.resolve(null, null, 0, lava()).id());
    }

    /** Engine index 0 is the vacuum/air sentinel — defer to the live block (mass guard handles emptiness). */
    @Test
    void fallsBackToLiveOnVoidSentinel() {
        List<Material> lut = List.of(VOID, water());
        char[] outMat = {0};
        assertEquals(mat("water"), EngineOutSpecies.resolve(outMat, lut, 0, water()).id());
    }

    @Test
    void fallsBackToLiveWhenIndexOutOfRange() {
        List<Material> lut = List.of(VOID, water());
        char[] outMat = {5 /* >= lut.size() */};
        assertEquals(mat("lava"), EngineOutSpecies.resolve(outMat, lut, 0, lava()).id());
        // and when the cell index itself is past the engine array
        assertEquals(mat("lava"), EngineOutSpecies.resolve(new char[0], lut, 0, lava()).id());
    }
}
