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
        double[] e = new double[RegionMarshaller.CHUNK_N];
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
    void aCellTheEngineEmptiedRecordsVacuumNotItsOldSpecies(@TempDir Path dir) {
        // The engine reports an emptied cell as species 0 with mass 0. Reading 0 as "no output" and falling
        // back to the input species left a drained lava cell labelled orge:lava at 0 kg (/orge get showed
        // "0.0 kg, block=minecraft:air, material=orge:lava").
        SectionStoreManager mgr = loaded(dir);
        ColumnTask task = sandTask();
        int drained = ColumnSectionCodec.colIdx(6, SECTION_Y, 2, 3);
        ColumnResult r = result(task, new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N]);
        r.matIx()[drained] = 0;
        r.mass()[drained] = 0f;
        world(mgr).writeBackColumn(new ThermalWorld.ColumnEntry(DIM, 0, 0, task), r);
        SectionData d = mgr.store(DIM).get(new SubchunkKey(0, SECTION_Y, 0));
        assertEquals(MaterialPalette.VACUUM_ID, d.materialAt(6 + 16 * 2 + 256 * 3), "the emptied cell is vacuum");
        assertEquals(SAND, d.materialAt(7 + 16 * 2 + 256 * 3), "an occupied neighbour keeps its species");
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
    @Test
    void restCountRoundTripsThroughWriteBackStoreAndAssembly(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir);
        ColumnTask task = sandTask();
        int calm = ColumnSectionCodec.colIdx(3, SECTION_Y, 5, 7);
        int nan = ColumnSectionCodec.colIdx(4, SECTION_Y, 5, 7);
        int neg = ColumnSectionCodec.colIdx(5, SECTION_Y, 5, 7);
        float[] rest = new float[RegionMarshaller.CHUNK_N];
        rest[calm] = 7f; rest[nan] = Float.NaN; rest[neg] = -3f;
        ColumnResult r0 = result(task, new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N]);
        ColumnResult r = new ColumnResult(r0.matIx(), r0.mass(), r0.temperature(), r0.momX(), r0.momY(),
                r0.momZ(), r0.p(), r0.swapReady(), r0.enthalpy(), r0.slotIx(), r0.slotMass(), rest);

        world(mgr).writeBackColumn(new ThermalWorld.ColumnEntry(DIM, 0, 0, task), r);

        SectionStore store = mgr.store(DIM);
        SectionData d = store.get(new SubchunkKey(0, SECTION_Y, 0));
        int sCalm = 3 + 16 * 5 + 256 * 7;
        assertEquals(7f, d.restCountAt(sCalm), "the engine's rest counter reached the store");
        assertEquals(0f, d.restCountAt(sCalm + 1), "non-finite → 0");
        assertEquals(0f, d.restCountAt(sCalm + 2), "negative → 0");

        // ... and comes back into the next step's ColumnTask through the reader + assembler.
        Material sand = lut().get(SAND_IX);
        MaterialLut mlut = net.rainbowcreation.orge.engine.TestMaterials.lutOf(lut());
        net.rainbowcreation.orge.material.MaterialRegistry reg =
                net.rainbowcreation.orge.engine.TestMaterials.registryOf(lut());
        GeometryAssembler.CellMaterials live = i -> sand;
        GeometryAssembler.Geometry geo = GeometryAssembler.assemble(live, mlut);
        ColumnTask next = ColumnAssembler.assemble(0, 0, mlut, reg, (cx, cz, sy) ->
                SectionCellsReader.read(store, new SubchunkKey(cx, sy, cz), geo, live,
                        id -> reg.get(id).orElse(null),
                        new char[SectionData.CELLS], new float[SectionData.CELLS], 290f));
        assertEquals(7f, next.restCount()[calm]);
        assertEquals(0f, next.restCount()[nan]);
        assertEquals(0f, next.restCount()[neg]);
        mgr.onLevelUnload(DIM);
        SectionData reloaded = loaded(dir).store(DIM).get(new SubchunkKey(0, SECTION_Y, 0));
        assertEquals(0f, reloaded.restCountAt(sCalm), "in-memory only: a reloaded section starts at 0");
    }

    // -------------------------------------------------------------------------
    // Issue #51: the write-back prepared off-thread from the snapshot's capture
    // -------------------------------------------------------------------------

    /** The column as the snapshot captures it, headless: each section's store view, no live blocks. */
    private static CapturedColumn capture(SectionStore store) {
        CapturedColumn.Section[] secs =
                new CapturedColumn.Section[ColumnAssembler.MAX_SECTION_Y - ColumnAssembler.MIN_SECTION_Y + 1];
        for (int sy = ColumnAssembler.MIN_SECTION_Y; sy <= ColumnAssembler.MAX_SECTION_Y; sy++) {
            SubchunkKey key = new SubchunkKey(0, sy, 0);
            boolean stored = store.hasSection(key);
            SectionData live = store.get(key);
            if (stored) live.markSnapshot();
            secs[sy - ColumnAssembler.MIN_SECTION_Y] = new CapturedColumn.Section(null, 290f, null, live.share(), stored);
        }
        return new CapturedColumn(DIM, 0, 0, secs, List.of());
    }

    private static ColumnResult oneComponent(ColumnTask task) {
        return result(task, new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N]);
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    /**
     * Preparing touches no store state (it runs on the step thread); applying installs the result on the
     * server thread without per-cell work — a whole column allocates a few KB there, not the ~MB of
     * per-section slices the server thread used to build (issue #51).
     */
    @Test
    void capturedWriteBackIsPreparedPureAndAppliedWithoutPerCellAllocation(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir);
        SectionStore store = mgr.store(DIM);
        MinecraftThermalWorld w = world(mgr);
        ColumnTask task = sandTask();
        SubchunkKey key = new SubchunkKey(0, SECTION_Y, 0);

        // warm-up cycle (class init / JIT), then the measured one
        w.prepareWriteBack(new ThermalWorld.ColumnEntry(DIM, 0, 0, task, new WaterlogSeeding.Seeds(),
                capture(store)), oneComponent(task), lut()).apply();
        store.get(key).setMass(0, 1f);                         // a stored state the next result overwrites

        ThermalWorld.PreparedWrite pw = w.prepareWriteBack(new ThermalWorld.ColumnEntry(DIM, 0, 0, task,
                new WaterlogSeeding.Seeds(), capture(store)), oneComponent(task), lut());
        assertEquals(1f, store.get(key).massAt(0), "preparing writes nothing to the store");

        long before = allocatedBytes();
        pw.apply();
        long bytes = allocatedBytes() - before;
        assertTrue(bytes < 64 * 1024, "installing one column allocated " + bytes + " bytes on the server thread");

        SectionData d = store.get(key);
        assertEquals(1600f, d.massAt(0), "the engine's mass is installed");
        assertEquals(3.9e8f, d.enthalpyAt(0), 0.0, "its energy");
        assertEquals(SAND, d.materialAt(0), "its species");
    }

    /** An edit landing after the capture keeps its section: the prepared result is stale there (issue #51
     *  moves the preparation off-thread; the edit-epoch guard still runs at install time). */
    @Test
    void capturedWriteBackSkipsASectionEditedAfterTheCapture(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir);
        SectionStore store = mgr.store(DIM);
        MinecraftThermalWorld w = world(mgr);
        ColumnTask task = sandTask();
        SubchunkKey key = new SubchunkKey(0, SECTION_Y, 0);
        w.writeBackColumn(new ThermalWorld.ColumnEntry(DIM, 0, 0, task), oneComponent(task));

        ThermalWorld.PreparedWrite pw = w.prepareWriteBack(new ThermalWorld.ColumnEntry(DIM, 0, 0, task,
                new WaterlogSeeding.Seeds(), capture(store)), oneComponent(task), lut());
        SectionData live = store.get(key);
        live.setEnthalpy(5, 42.0);                             // /orge set after the capture
        live.markExternalEdit();
        pw.apply();

        assertEquals(42.0, store.get(key).enthalpyAt(5), 0.0, "the edit survives the stale write-back");
    }
}
