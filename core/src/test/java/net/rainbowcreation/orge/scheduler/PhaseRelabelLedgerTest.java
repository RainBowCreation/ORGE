package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine now ALWAYS relabels a cell past its phase threshold, keeping its mass (DESIGN-LAW #9
 * v4.4). The §9 per-species gate must read that as a conversion, not as species A losing mass and
 * species B inventing it — otherwise every region with a relabel is HELD forever.
 */
class PhaseRelabelLedgerTest {

    private static Identifier orge(String p) { return Identifier.fromNamespaceAndPath("orge", p); }

    private static Material.Builder base(String p, float m) {
        return Material.builder(orge(p)).thermalConductivity(1f).heatCapacity(1f).molarMass(0.02f)
                .defaultMass(m).defaultTemperature(Float.NaN);
    }

    private static final char LAVA = 1, STONE = 2, WATER = 3, ICE = 4, STEAM = 5;

    private static List<Material> lut() {
        return List.of(MaterialLut.VACUUM,
                base("lava", 2650f).viscosity(500f).minMass(330f).maxMass(2650f)
                        .minTemp(1275f).minTarget(orge("stone")).build(),
                base("stone", 2700f).maxTemp(1450f).maxTarget(orge("lava")).build(),
                base("water", 1000f).viscosity(0.001f).minMass(125f).maxMass(1000f)
                        .minTemp(273f).minTarget(orge("ice"))
                        .maxTemp(373f).maxTarget(orge("steam")).build(),
                base("ice", 917f).maxTemp(273f).maxTarget(orge("water")).build(),
                base("steam", 0.6f).viscosity(1.3e-5f).minMass(0.06f).maxMass(1000f)
                        .minTemp(373f).minTarget(orge("water")).build());
    }

    private static boolean conserved(float[] before, float[] after, char[] in, char[] out) {
        return StepValidator.massConservedPerSpecies(after, before, in, out, lut());
    }

    @Test
    void lavaSolidifyingKeepMassIsConserved() {
        assertTrue(conserved(new float[]{2650f}, new float[]{2650f}, new char[]{LAVA}, new char[]{STONE}));
    }

    @Test
    void waterFreezingWithSurplusEvictedToWaterIsConserved() {
        // cell 0: water 1000 -> ice 917; cell 1: water neighbour receives the 83 kg surplus.
        assertTrue(conserved(new float[]{1000f, 500f}, new float[]{917f, 583f},
                new char[]{WATER, WATER}, new char[]{ICE, WATER}));
    }

    @Test
    void boilingAndCondensingAreConserved() {
        assertTrue(conserved(new float[]{800f}, new float[]{800f}, new char[]{WATER}, new char[]{STEAM}));
        assertTrue(conserved(new float[]{0.6f}, new float[]{0.6f}, new char[]{STEAM}, new char[]{WATER}));
    }

    @Test
    void nonPhaseSpeciesChangeWithMassIsStillCaught() {
        // water is not a phase target of lava's chain here: a water cell "becoming" lava fabricates lava.
        assertFalse(conserved(new float[]{1000f}, new float[]{1000f}, new char[]{WATER}, new char[]{LAVA}));
    }
}
