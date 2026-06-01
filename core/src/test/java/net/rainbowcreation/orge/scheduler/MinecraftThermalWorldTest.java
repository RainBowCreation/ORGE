package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepResult;
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
 * Headless tests for the {@link MinecraftThermalWorld} write-back path (the only part that
 * touches no live server). Uses the real §5 {@link SectionStoreManager} with a temp dir.
 */
class MinecraftThermalWorldTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    private static final Identifier ORGE_VOID = Identifier.fromNamespaceAndPath("orge", "void");
    private static final Identifier ORGE_AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ORGE_WATER = Identifier.fromNamespaceAndPath("orge", "water");

    private static final char VOID_IX = 0;
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

    /** LUT: void=0, air=1, water=2 (the indices used by the recordCellMaterials tests). */
    private static List<Material> recordLut() {
        return List.of(MaterialLut.VOID, nonFluid(ORGE_AIR, 1.2f), fluid(ORGE_WATER, 1000f));
    }

    private static SectionStoreManager loadedManager(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0);
        return mgr;
    }

    @Test
    void writeBackPersistsGeometryMassNotJustTemperature(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        float[] temps = new float[SectionData.CELLS];
        Arrays.fill(temps, 350f);
        float[] mass = new float[SectionData.CELLS];
        Arrays.fill(mass, 1000f); // geometry mass (e.g. water's default_mass)
        StepTask task = new StepTask(key, new char[SectionData.CELLS], mass, temps, null);
        ThermalWorld.BatchEntry entry = new ThermalWorld.BatchEntry(DIM, key, task);

        world.writeBack(entry, new StepResult(temps, mass));

        SectionData data = mgr.store(DIM).get(key);
        assertEquals(350f, data.temperatureAt(0), 1e-4f, "temperature persisted");
        assertEquals(1000f, data.massAt(0), 1e-4f, "engine mass must be persisted, not left at 0");
        assertEquals(1000f, data.massAt(SectionData.CELLS - 1), 1e-4f, "all cells carry their mass");
    }

    @Test
    void writeBackDemotesToUniformWhenEngineFlattensAllCells(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        float[] temps = new float[SectionData.CELLS];
        Arrays.fill(temps, 300f);
        float[] mass = new float[SectionData.CELLS];
        Arrays.fill(mass, 1000f);
        StepTask task = new StepTask(key, new char[SectionData.CELLS], mass, temps, null);
        ThermalWorld.BatchEntry entry = new ThermalWorld.BatchEntry(DIM, key, task);

        world.writeBack(entry, new StepResult(temps, mass));

        SectionData data = mgr.store(DIM).get(key);
        assertEquals(SectionData.Form.UNIFORM, data.form(),
                "a section the engine flattened to a single value must collapse back to UNIFORM, "
                        + "not ratchet at FULL forever");
        assertEquals(300f, data.temperatureAt(0), 1e-4f, "uniform value preserved through demote");
        assertEquals(1000f, data.massAt(0), 1e-4f, "uniform mass preserved through demote");
    }

    @Test
    void writeBackStaysFullWhenAGradientRemains(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        float[] temps = new float[SectionData.CELLS];
        Arrays.fill(temps, 300f);
        temps[0] = 350f; // a real gradient — must NOT collapse
        float[] mass = new float[SectionData.CELLS];
        Arrays.fill(mass, 1000f);
        StepTask task = new StepTask(key, new char[SectionData.CELLS], mass, temps, null);
        ThermalWorld.BatchEntry entry = new ThermalWorld.BatchEntry(DIM, key, task);

        world.writeBack(entry, new StepResult(temps, mass));

        SectionData data = mgr.store(DIM).get(key);
        assertEquals(SectionData.Form.FULL, data.form(),
                "a section holding a genuine gradient must stay FULL");
        assertEquals(350f, data.temperatureAt(0), 1e-4f);
        assertEquals(300f, data.temperatureAt(1), 1e-4f);
    }

    /** D is an input air cell the engine wetted (outMat[D]=water). The recorded signature must be
     *  the engine OUTPUT species (water) — so the reconciler's matching water placement next
     *  snapshot is recognised and NOT reseeded — not the pre-step input block (air). */
    @Test
    void recordCellMaterialsRecordsEngineOutputSpeciesNotInputBlock(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        CellMaterialTracker tracker = new CellMaterialTracker();
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr, tracker, new ActiveSet());
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        int D = 100;
        char[] inMat = new char[SectionData.CELLS];
        Arrays.fill(inMat, AIR_IX); // all cells are air in the pre-step world
        StepTask task = new StepTask(key, inMat,
                new float[SectionData.CELLS], new float[SectionData.CELLS], null);
        ThermalWorld.BatchEntry entry = new ThermalWorld.BatchEntry(DIM, key, task);

        char[] outMat = new char[SectionData.CELLS]; // engine produced nothing...
        outMat[D] = WATER_IX;                        // ...except it wetted D to water.

        world.recordCellMaterials(entry, outMat, recordLut());

        Identifier[] prior = tracker.prior(DIM, key);
        assertNotNull(prior, "signature recorded");
        assertEquals(ORGE_WATER, prior[D], "D records the engine OUTPUT species (water), not input air");
    }

    /** An untouched air cell (outMat[E]=0) records the WORLD material (orge:air, from input matIx),
     *  never the index-0 orge:void sentinel. */
    @Test
    void recordCellMaterialsFallsBackToWorldMaterialForUntouchedAirCells(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        CellMaterialTracker tracker = new CellMaterialTracker();
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr, tracker, new ActiveSet());
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        int E = 200;
        char[] inMat = new char[SectionData.CELLS];
        Arrays.fill(inMat, AIR_IX);
        StepTask task = new StepTask(key, inMat,
                new float[SectionData.CELLS], new float[SectionData.CELLS], null);
        ThermalWorld.BatchEntry entry = new ThermalWorld.BatchEntry(DIM, key, task);

        char[] outMat = new char[SectionData.CELLS]; // outMat[E] == 0: engine deposited nothing at E

        world.recordCellMaterials(entry, outMat, recordLut());

        Identifier[] prior = tracker.prior(DIM, key);
        assertNotNull(prior, "signature recorded");
        assertEquals(ORGE_AIR, prior[E], "untouched air cell records orge:air (input), not orge:void");
        assertNotEquals(ORGE_VOID, prior[E], "must not record the index-0 void sentinel");
    }
}
