package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for "mass from nothing" at the {@link CellMaterialTracker} + {@link
 * MaterialChangeReseed} seam (DESIGN §10 follow-on; the reseed-misfire fix). It exercises the exact
 * two-cycle sequence the live bug walked, without a running server:
 *
 * <ol>
 *   <li>Cycle N: the engine wets a previously-air cell to water (outMat=water, deposits a small
 *       mass). {@link MinecraftThermalWorld#recordCellMaterials} records the engine OUTPUT species
 *       (water) as the cell's signature — NOT the pre-step air block.</li>
 *   <li>Cycle N+1: the reconciler has placed a {@code water} block at that cell, so the live world
 *       material now matches the recorded signature (water == water). {@link MaterialChangeReseed}
 *       must therefore leave the small advected mass UNCHANGED (no reseed → mass is conserved).</li>
 * </ol>
 *
 * <p>Contrast: a cell that became water via an EXTERNAL bucket (the engine never deposited there, so
 * its prior signature is still {@code orge:air}) has its stale air mass CLEARED to 0 (Task 6 / DESIGN
 * 2026-06-01 §6) — it is then seeded to {@code water.defaultMass()} (1000) by the single fresh-fluid
 * seed in {@link ColumnAssembler}, so the legitimate "1 bucket = 1000 kg" entry point is preserved
 * with exactly one mass seed (no fabrication at this seam).</p>
 */
class MaterialChangeReseedConservationTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier ORGE_AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ORGE_WATER = Identifier.fromNamespaceAndPath("orge", "water");

    private static final char AIR_IX = 1;
    private static final char WATER_IX = 2;

    private static Material fluid(Identifier id, float defaultMass) {
        return Material.builder(id)
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(defaultMass).defaultTemperature(Float.NaN)
                .viscosity(0f) // finite ⇒ movable (old fluid=true)
                .build();
    }

    private static Material nonFluid(Identifier id, float defaultMass) {
        return Material.builder(id)
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(defaultMass).defaultTemperature(Float.NaN)
                .build(); // no viscosity ⇒ frozen
    }

    /** LUT: void=0, air=1, water=2. */
    private static List<Material> lut() {
        return List.of(MaterialLut.VACUUM, nonFluid(ORGE_AIR, 1.2f), fluid(ORGE_WATER, 1000f));
    }

    private static SectionStoreManager loadedManager(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0);
        return mgr;
    }

    @Test
    void engineWettedCellIsNotReseededButExternalBucketIs(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        CellMaterialTracker tracker = new CellMaterialTracker();
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr, tracker, new ActiveSet());
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        int SPREAD = 100;   // engine wetted this air cell to water (reconciler-placed next cycle)
        int BUCKET = 200;   // a player bucketed water here externally next cycle

        // --- Cycle N: both cells are air in the input world; the engine wets only SPREAD. ---
        char[] inMat = new char[SectionData.CELLS];
        Arrays.fill(inMat, AIR_IX);
        StepTask task = new StepTask(key, inMat,
                new float[SectionData.CELLS], new float[SectionData.CELLS], null);
        ThermalWorld.BatchEntry entry = new ThermalWorld.BatchEntry(DIM, key, task);

        char[] outMat = new char[SectionData.CELLS]; // engine deposited nothing...
        outMat[SPREAD] = WATER_IX;                   // ...except SPREAD became water (a thin spread).

        world.recordCellMaterials(entry, outMat, lut());

        Identifier[] prior = tracker.prior(DIM, key);
        assertNotNull(prior, "cycle-N signature recorded");
        assertEquals(ORGE_WATER, prior[SPREAD], "SPREAD signature is the engine output species (water)");
        assertEquals(ORGE_AIR, prior[BUCKET], "BUCKET signature is still air (engine never touched it)");

        // --- Cycle N+1: BOTH cells are now water blocks (SPREAD via reconciler, BUCKET via player). ---
        char[] liveMat = new char[SectionData.CELLS];
        Arrays.fill(liveMat, AIR_IX);
        liveMat[SPREAD] = WATER_IX;
        liveMat[BUCKET] = WATER_IX;

        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        Arrays.fill(temps, 290f);
        float spreadMass = 50f;       // the small advected mass the engine deposited at SPREAD
        mass[SPREAD] = spreadMass;
        mass[BUCKET] = 1.2f;          // BUCKET still carries the OLD air block's stale stored mass

        MaterialChangeReseed.apply(prior, liveMat, lut(), temps, mass, 285f);

        assertEquals(spreadMass, mass[SPREAD], 0f,
                "engine-wetted cell keeps its small advected mass (no reseed → conservation)");
        assertEquals(0f, mass[BUCKET], 0f,
                "external bucket cell (prior=air) is CLEARED to 0 (ColumnAssembler then seeds 1000)");
    }
}
