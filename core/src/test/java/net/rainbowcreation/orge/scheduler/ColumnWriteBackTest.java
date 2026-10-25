package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ColumnWriteBack} persistence of the DESIGN-LAW v5 #7 composition slots (issue #27): the engine's
 * slot 1..K−1 output reaches the {@link SectionStore}, survives a save → reload through the region files,
 * and a one-component column never allocates a composition layer.
 */
class ColumnWriteBackTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier SAND = Identifier.fromNamespaceAndPath("orge", "sand");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");

    private static final char SAND_IX = 1, WATER_IX = 2, AIR_IX = 3;
    private static final int SECTION_Y = 4;

    private static Material mat(Identifier id, float defaultMass) {
        return Material.builder(id)
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(defaultMass).defaultTemperature(Float.NaN)
                .build();
    }

    /** Batch LUT: vacuum=0, sand=1, water=2, air=3. */
    private static List<Material> lut() {
        return List.of(MaterialLut.VACUUM, mat(SAND, 1600f), mat(WATER, 1000f), mat(AIR, 1.2f));
    }

    private static SectionStoreManager loaded(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0);
        return mgr;
    }

    /** A column whose section {@link #SECTION_Y} is all sand (1600 kg); the rest vacuum. */
    private static ColumnTask sandTask() {
        char[] matIx = new char[RegionMarshaller.CHUNK_N];
        float[] mass = new float[RegionMarshaller.CHUNK_N];
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 290f);
        for (int z = 0; z < 16; z++)
            for (int sy = 0; sy < 16; sy++)
                for (int x = 0; x < 16; x++) {
                    int ci = ColumnSectionCodec.colIdx(x, SECTION_Y, sy, z);
                    matIx[ci] = SAND_IX;
                    mass[ci] = 1600f;
                }
        return new ColumnTask(0, 0, matIx, mass, temp);
    }

    private static ColumnResult result(ColumnTask t, char[] slotIx, float[] slotMass) {
        float[] e = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(e, 3.9e8f);
        float[] z = new float[RegionMarshaller.CHUNK_N];
        return new ColumnResult(t.matIx().clone(), t.mass().clone(), t.temperature().clone(),
                z, z, z, z, z, e, slotIx, slotMass);
    }

    private static MinecraftThermalWorld world(SectionStoreManager mgr) {
        MinecraftThermalWorld w = new MinecraftThermalWorld(mgr);
        w.setLastColumnLutForTest(lut());
        return w;
    }

    @Test
    void engineSlotsPersistAndSurviveSaveReload(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir);
        ColumnTask task = sandTask();
        int n = RegionMarshaller.CHUNK_N;
        int wet = ColumnSectionCodec.colIdx(3, SECTION_Y, 5, 7);
        int wetter = ColumnSectionCodec.colIdx(9, SECTION_Y, 0, 1);
        char[] slotIx = new char[RegionMarshaller.SLOT_N];
        float[] slotMass = new float[RegionMarshaller.SLOT_N];
        slotIx[wet] = WATER_IX;              slotMass[wet] = 310.25f;        // slot 1: pore water
        slotIx[wetter] = WATER_IX;           slotMass[wetter] = 290f;
        slotIx[2 * n + wetter] = AIR_IX;     slotMass[2 * n + wetter] = 0.125f; // slot 3: trapped air

        world(mgr).writeBackColumn(new ThermalWorld.ColumnEntry(DIM, 0, 0, task),
                result(task, slotIx, slotMass));

        int sWet = 3 + 16 * 5 + 256 * 7, sWetter = 9 + 256;
        SectionData live = mgr.store(DIM).get(new SubchunkKey(0, SECTION_Y, 0));
        assertTrue(live.hasSlots(), "the engine's composition reached the store");
        assertEquals(WATER, live.slotMaterialAt(1, sWet));
        assertEquals(310.25f, live.slotMassAt(1, sWet));
        assertEquals(1600f + 310.25f, live.cellMassAt(sWet), "M = Σ_k m_k");

        mgr.onLevelUnload(DIM);              // save to the region files + close
        SectionStoreManager reloaded = loaded(dir);
        SectionData back = reloaded.store(DIM).get(new SubchunkKey(0, SECTION_Y, 0));
        assertTrue(back.hasSlots(), "composition survives save → reload");
        double liveM = 0, backM = 0;
        for (int i = 0; i < SectionData.CELLS; i++) {
            assertEquals(SAND, back.materialAt(i));
            assertEquals(live.massAt(i), back.massAt(i));
            assertEquals(live.enthalpyAt(i), back.enthalpyAt(i));
            for (int k = 1; k < SectionData.SLOTS; k++) {
                assertEquals(live.slotMaterialAt(k, i), back.slotMaterialAt(k, i), "slot " + k + " id " + i);
                assertEquals(live.slotMassAt(k, i), back.slotMassAt(k, i), "slot " + k + " mass " + i);
            }
            liveM += live.cellMassAt(i);
            backM += back.cellMassAt(i);
        }
        assertEquals(liveM, backM, "grand mass identical after reload");
        assertEquals(WATER, back.slotMaterialAt(1, sWetter));
        assertEquals(MaterialPalette.VACUUM_ID, back.slotMaterialAt(2, sWetter), "empty slot stays empty");
        assertEquals(AIR, back.slotMaterialAt(3, sWetter));
        assertEquals(0.125f, back.slotMassAt(3, sWetter));
        reloaded.onLevelUnload(DIM);
    }

    @Test
    void oneComponentResultAllocatesNoCompositionLayer(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir);
        ColumnTask task = sandTask();
        world(mgr).writeBackColumn(new ThermalWorld.ColumnEntry(DIM, 0, 0, task),
                result(task, new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N]));
        assertFalse(mgr.store(DIM).get(new SubchunkKey(0, SECTION_Y, 0)).hasSlots(),
                "one-component cells stay layer-free");
    }

    @Test
    void slotsThatEmptyReleaseTheLayerAndBadMassIsSanitized(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir);
        MinecraftThermalWorld w = world(mgr);
        ColumnTask task = sandTask();
        int a = ColumnSectionCodec.colIdx(1, SECTION_Y, 1, 1);
        int b = ColumnSectionCodec.colIdx(2, SECTION_Y, 1, 1);
        int c = ColumnSectionCodec.colIdx(4, SECTION_Y, 1, 1);
        char[] slotIx = new char[RegionMarshaller.SLOT_N];
        float[] slotMass = new float[RegionMarshaller.SLOT_N];
        slotIx[a] = WATER_IX; slotMass[a] = 5f;
        slotIx[b] = WATER_IX; slotMass[b] = Float.NaN;   // engine leak: never persisted as mass
        slotIx[c] = WATER_IX; slotMass[c] = -3f;
        w.writeBackColumn(new ThermalWorld.ColumnEntry(DIM, 0, 0, task), result(task, slotIx, slotMass));
        SubchunkKey key = new SubchunkKey(0, SECTION_Y, 0);
        SectionData d = mgr.store(DIM).get(key);
        assertEquals(5f, d.slotMassAt(1, 1 + 16 + 256));
        assertEquals(0f, d.slotMassAt(1, 2 + 16 + 256), "non-finite slot mass → 0");
        assertEquals(MaterialPalette.VACUUM_ID, d.slotMaterialAt(1, 2 + 16 + 256), "a 0-mass slot is empty");
        assertEquals(0f, d.slotMassAt(1, 4 + 16 + 256), "negative slot mass → 0");

        // Next cycle the pore water has left: the stored slot empties and the layer is released.
        w.writeBackColumn(new ThermalWorld.ColumnEntry(DIM, 0, 0, task),
                result(task, new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N]));
        SectionData after = mgr.store(DIM).get(key);
        assertEquals(0f, after.slotMassAt(1, 1 + 16 + 256), "the stored slot was overwritten empty");
        assertFalse(after.hasSlots(), "an all-empty layer is released");
    }
}
