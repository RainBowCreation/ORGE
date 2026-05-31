package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless tests for the {@link MinecraftThermalWorld} write-back path (the only part that
 * touches no live server). Uses the real §5 {@link SectionStoreManager} with a temp dir.
 */
class MinecraftThermalWorldTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

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
}
