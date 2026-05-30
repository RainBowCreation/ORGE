package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link MaterialChangeReseed}: when a cell's live material becomes a DIFFERENT
 * fluid since the previous cycle (e.g. air→water from a bucket, lava replacing water), the §5 store
 * still holds the OLD block's temp/mass (air's 1.2 kg / ambient). This unit overrides those stale
 * values with the new material's defaults. Non-fluid materials and unchanged cells are left alone so
 * ORGE's own phase transitions (→ stone/ice/steam, all non-fluid) and ongoing flow are never
 * disturbed.
 */
class MaterialChangeReseedTest {

    private static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier LAVA = Identifier.fromNamespaceAndPath("orge", "lava");

    private static final char VOID_IX = 0;
    private static final char WATER_IX = 1;
    private static final char LAVA_IX = 2;
    private static final char STONE_IX = 3;

    /** A fluid material (no source temperature) with the given id + defaultMass. */
    private static Material fluid(Identifier id, float defaultMass) {
        return new Material(id, 1f, 1f, 0f, defaultMass, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null,
                Float.NaN, false, true);
    }

    /** A fluid SOURCE material (pinned default_temperature), like lava. */
    private static Material fluidSource(Identifier id, float defaultMass, float defaultTemp) {
        return new Material(id, 1f, 1f, 0f, defaultMass, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null,
                defaultTemp, true, true);
    }

    private static Material solid(Identifier id, float defaultMass) {
        return new Material(id, 1f, 1f, 0f, defaultMass, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
    }

    /** LUT: VOID=0, water=1, lava=2, stone=3. */
    private static List<Material> lut() {
        return List.of(MaterialLut.VOID,
                fluid(WATER, 1000f),
                fluidSource(LAVA, 3100f, 1400f),
                solid(Identifier.fromNamespaceAndPath("orge", "stone"), 2500f));
    }

    private static char[] uniform(char ix) {
        char[] m = new char[SectionData.CELLS];
        java.util.Arrays.fill(m, ix);
        return m;
    }

    private static Identifier[] uniformPrior(Identifier id) {
        Identifier[] p = new Identifier[SectionData.CELLS];
        java.util.Arrays.fill(p, id);
        return p;
    }

    @Test
    void nullPriorIsNoOp() {
        // First-ever tracking of a section: no signature, never re-seed (block seed already correct).
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 283f);
        java.util.Arrays.fill(mass, 1.2f);
        MaterialChangeReseed.apply(null, uniform(WATER_IX), lut(), temps, mass, 285f);
        assertEquals(283f, temps[0], 0f);
        assertEquals(1.2f, mass[0], 0f);
    }

    @Test
    void airToWaterReseedsMassToDefaultAndTempToAmbient() {
        // The reported bug: bucket water into a previously-air FULL cell. Store holds air's 1.2 kg.
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 283f);
        java.util.Arrays.fill(mass, 1.2f);  // air's stored mass
        MaterialChangeReseed.apply(uniformPrior(AIR), uniform(WATER_IX), lut(), temps, mass, 285f);
        assertEquals(1000f, mass[0], 0f, "water re-seeded to its defaultMass");
        assertEquals(285f, temps[0], 0f, "water (no source temp) seeds at biome ambient");
    }

    @Test
    void airToLavaReseedsMassAndPinnedTemperature() {
        // Bucket lava into air: must reach lava's pinned 1400 K (else it inherits cold 283 and the
        // phase planner freezes it to stone).
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 283f);
        java.util.Arrays.fill(mass, 1.2f);
        MaterialChangeReseed.apply(uniformPrior(AIR), uniform(LAVA_IX), lut(), temps, mass, 285f);
        assertEquals(3100f, mass[0], 0f, "lava re-seeded to its defaultMass");
        assertEquals(1400f, temps[0], 0f, "lava seeds at its pinned default_temperature, not ambient");
    }

    @Test
    void unchangedFluidCellIsPreserved() {
        // Ongoing flow: same material as last cycle -> keep the advected mass (no oscillation).
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 350f);
        java.util.Arrays.fill(mass, 123f);  // partially-drained advected mass
        MaterialChangeReseed.apply(uniformPrior(WATER), uniform(WATER_IX), lut(), temps, mass, 285f);
        assertEquals(123f, mass[0], 0f, "same-material fluid keeps advected mass");
        assertEquals(350f, temps[0], 0f, "same-material fluid keeps its temperature");
    }

    @Test
    void changeToNonFluidIsNotReseeded() {
        // ORGE's own transitions (water→ice/steam, lava→stone) yield NON-fluid blocks: leave the
        // post-transition temp/mass that §7 set, never override them here.
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 1000f);  // freshly-frozen-from-lava, still hot
        java.util.Arrays.fill(mass, 3100f);
        MaterialChangeReseed.apply(uniformPrior(LAVA), uniform(STONE_IX), lut(), temps, mass, 285f);
        assertEquals(1000f, temps[0], 0f, "transition to non-fluid (stone) preserves §7 temperature");
        assertEquals(3100f, mass[0], 0f, "transition to non-fluid preserves mass");
    }

    @Test
    void voidCellIsNeverReseeded() {
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 283f);
        java.util.Arrays.fill(mass, 0f);
        MaterialChangeReseed.apply(uniformPrior(AIR), uniform(VOID_IX), lut(), temps, mass, 285f);
        assertEquals(283f, temps[0], 0f);
        assertEquals(0f, mass[0], 0f);
    }

    @Test
    void onlyChangedFluidCellsAreOverridden() {
        // Mixed section: even cells stayed water (advecting), odd cells were air and got water bucketed.
        char[] matIx = uniform(WATER_IX);
        Identifier[] prior = new Identifier[SectionData.CELLS];
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            boolean wasWater = (i % 2 == 0);
            prior[i] = wasWater ? WATER : AIR;
            temps[i] = 300f;
            mass[i] = wasWater ? 250f : 1.2f;  // water: advected; air: stale
        }
        MaterialChangeReseed.apply(prior, matIx, lut(), temps, mass, 285f);
        for (int i = 0; i < SectionData.CELLS; i++) {
            if (i % 2 == 0) {
                assertEquals(250f, mass[i], 0f, "unchanged water cell " + i + " preserved");
            } else {
                assertEquals(1000f, mass[i], 0f, "air→water cell " + i + " re-seeded");
            }
        }
    }
}
