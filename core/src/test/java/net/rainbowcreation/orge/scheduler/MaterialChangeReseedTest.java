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
 * still holds the OLD block's temp/mass (air's 1.2 kg / ambient). This unit corrects the stale
 * temperature to the new material's source/ambient seed and <b>clears the stale stored mass to 0</b>
 * (DESIGN 2026-06-01 §6 / Task 6): it no longer fabricates {@code defaultMass} — the single surviving
 * fresh-fluid mass seed lives in {@link ColumnAssembler} ({@code fluid && stored <= 0 ⇒ defaultMass}),
 * which fills the cleared cell. Non-fluid materials and unchanged cells are left alone so ORGE's own
 * phase transitions (→ stone/ice/steam, all non-fluid) and ongoing flow are never disturbed.
 */
class MaterialChangeReseedTest {

    private static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier LAVA = Identifier.fromNamespaceAndPath("orge", "lava");

    private static final char VOID_IX = 0;
    private static final char WATER_IX = 1;
    private static final char LAVA_IX = 2;
    private static final char STONE_IX = 3;
    private static final char AIR_IX = 4;

    /** First-class AIR (State.AIR): air()==true, fluid()==false. The §11 ambient finite gas. */
    private static Material air(Identifier id, float defaultMass) {
        return new Material(id, 1f, 1f, 0f, defaultMass, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null,
                Float.NaN, false, Material.State.AIR, 0.001f, 1000f);
    }

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

    /** LUT: VOID=0, water=1, lava=2, stone=3, air=4. */
    private static List<Material> lut() {
        return List.of(MaterialLut.VOID,
                fluid(WATER, 1000f),
                fluidSource(LAVA, 3100f, 1400f),
                solid(Identifier.fromNamespaceAndPath("orge", "stone"), 2500f),
                air(AIR, 1.2f));
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
    void airToWaterClearsStaleMassToZeroAndSeedsTempToAmbient() {
        // The reported bug: bucket water into a previously-air FULL cell. Store holds air's 1.2 kg.
        // Task 6: the stale mass is CLEARED to 0 (ColumnAssembler's seed then fills it to 1000); the
        // temperature is corrected to biome ambient. No mass is fabricated here.
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 283f);
        java.util.Arrays.fill(mass, 1.2f);  // air's stored mass
        MaterialChangeReseed.apply(uniformPrior(AIR), uniform(WATER_IX), lut(), temps, mass, 285f);
        assertEquals(0f, mass[0], 0f, "stale mass cleared to 0 (ColumnAssembler seeds defaultMass)");
        assertEquals(285f, temps[0], 0f, "water (no source temp) seeds at biome ambient");
    }

    @Test
    void airToLavaClearsStaleMassToZeroAndSeedsPinnedTemperature() {
        // Bucket lava into air: must reach lava's pinned 1400 K (else it inherits cold 283 and the
        // phase planner freezes it to stone). Mass is cleared to 0; ColumnAssembler seeds the 3100.
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 283f);
        java.util.Arrays.fill(mass, 1.2f);
        MaterialChangeReseed.apply(uniformPrior(AIR), uniform(LAVA_IX), lut(), temps, mass, 285f);
        assertEquals(0f, mass[0], 0f, "stale mass cleared to 0 (ColumnAssembler seeds defaultMass)");
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

    // --- §11 Phase A (Task M4): a RUNTIME block→air transition makes VACUUM, not air-from-nothing. ---

    @Test
    void brokenBlockToAirBecomesVacuumNotAir() {
        // A player breaks a stone block: the live cell is now air, but the §5 store still holds the
        // OLD stone mass (2500). The new rule: the cell becomes VACUUM (matIx 0 = void, mass 0), NOT
        // 1.2 kg air from nothing. Neighbouring air refills it in the engine (conserved).
        char[] matIx = uniform(AIR_IX);
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 290f);
        java.util.Arrays.fill(mass, 2500f);  // stale stone mass
        MaterialChangeReseed.apply(uniformPrior(Identifier.fromNamespaceAndPath("orge", "stone")),
                matIx, lut(), temps, mass, 285f);
        assertEquals(VOID_IX, matIx[0], "broken block→air cell becomes the void sentinel (matIx 0)");
        assertEquals(0f, mass[0], 0f, "broken block→air cell is VACUUM (0 mass), not 1.2 kg air");
    }

    @Test
    void chunkLoadAirSeedingIsUntouchedAndDiffersFromBrokenBlock() {
        // The two air paths MUST differ. Chunk-load / world-gen seeding (prior == null, the section
        // was never tracked) leaves the block-derived 1.2 kg air seed alone — the world comes WITH
        // its air. A runtime break (prior = a real block) makes vacuum. Same live air cell, opposite
        // mass outcome, gated solely on whether the cell was tracked before.
        char[] seedMat = uniform(AIR_IX);
        float[] seedMass = new float[SectionData.CELLS];
        java.util.Arrays.fill(seedMass, 1.2f);  // the world-gen / block-derived air seed
        MaterialChangeReseed.apply(null, seedMat, lut(),
                new float[SectionData.CELLS], seedMass, 285f);
        assertEquals(AIR_IX, seedMat[0], "chunk-load air keeps its real air material (not void)");
        assertEquals(1.2f, seedMass[0], 0f, "chunk-load / world-gen air seeding stays 1.2 kg (unchanged)");

        // Same live air cell, but tracked (a runtime break) → vacuum. The paths genuinely diverge.
        char[] runtimeMat = uniform(AIR_IX);
        float[] runtimeMass = new float[SectionData.CELLS];
        java.util.Arrays.fill(runtimeMass, 2500f);
        MaterialChangeReseed.apply(uniformPrior(Identifier.fromNamespaceAndPath("orge", "stone")),
                runtimeMat, lut(), new float[SectionData.CELLS], runtimeMass, 285f);
        assertNotEquals(seedMass[0], runtimeMass[0],
                "world-gen air (1.2) and broken-block vacuum (0) must differ");
        assertEquals(0f, runtimeMass[0], 0f, "the broken-block path is vacuum");
    }

    @Test
    void engineAirFillIsNotMistakenForAPlayerEditAndNotReVacuumed() {
        // Reseed-misfire guard (mirrors MaterialChangeReseedConservationTest): the engine filled a
        // broken-block vacuum with air over a step (the cell's recorded signature is the engine
        // OUTPUT species = air). Next snapshot the live cell is air and prior is ALSO air, so this
        // unit must NOT re-vacuum it (no air→air transition) — the fill is not fought.
        char[] matIx = uniform(AIR_IX);
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 290f);
        java.util.Arrays.fill(mass, 0.8f);  // air the engine flowed in (still equalising)
        MaterialChangeReseed.apply(uniformPrior(AIR), matIx, lut(), temps, mass, 285f);
        assertEquals(AIR_IX, matIx[0], "engine-filled air cell stays air (not re-vacuumed)");
        assertEquals(0.8f, mass[0], 0f, "engine-filled air mass is preserved (the fill is not fought)");
    }

    @Test
    void brokenBlockToVoidStaysVacuum() {
        // A cell that became the void sentinel directly (matIx 0) is already vacuum: leave it.
        char[] matIx = uniform(VOID_IX);
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        java.util.Arrays.fill(temps, 283f);
        java.util.Arrays.fill(mass, 0f);
        MaterialChangeReseed.apply(uniformPrior(Identifier.fromNamespaceAndPath("orge", "stone")),
                matIx, lut(), temps, mass, 285f);
        assertEquals(VOID_IX, matIx[0]);
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
                assertEquals(0f, mass[i], 0f,
                        "air→water cell " + i + " cleared to 0 (ColumnAssembler seeds defaultMass)");
            }
        }
    }
}
