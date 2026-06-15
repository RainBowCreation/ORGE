package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;

import java.util.function.Function;
import net.rainbowcreation.orge.section.AmbientProvider;
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
 * Headless tests for the {@link MinecraftThermalWorld} write-back path (the only part that
 * touches no live server). Uses the real §5 {@link SectionStoreManager} with a temp dir.
 */
class MinecraftThermalWorldTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    private static final Identifier ORGE_VACUUM = Identifier.fromNamespaceAndPath("orge", "vacuum");
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

    /** LUT: vacuum=0, air=1, water=2 (the indices used by the recordCellMaterials tests). */
    private static List<Material> recordLut() {
        return List.of(MaterialLut.VACUUM, nonFluid(ORGE_AIR, 1.2f), fluid(ORGE_WATER, 1000f));
    }

    private static SectionStoreManager loadedManager(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0);
        return mgr;
    }

    /** A chain-root material with cp=1 ⇒ {@code h(T)=cp·T=T}, so the dormant writeBack's ENCODE
     *  {@code E=m·h(T)} reduces to {@code E=m·T} for clean assertions. */
    private static final Identifier ORGE_UNIT = Identifier.fromNamespaceAndPath("orge", "unit_cp");

    private static Material unitCp() {
        return Material.builder(ORGE_UNIT)
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(1000f).defaultTemperature(Float.NaN).viscosity(0f).build();
    }

    /** Register {@link #unitCp()} as the active LUT and stamp every cell of {@code key}'s section to it,
     *  so the dormant {@link MinecraftThermalWorld#writeBack} ENCODE has a curve to derive E against
     *  (E = m·cp·T = m·T). Returns the manager's store. */
    private static void seedUnitCpSection(SectionStoreManager mgr, SubchunkKey key) {
        net.rainbowcreation.orge.material.MaterialRegistry reg =
                new net.rainbowcreation.orge.material.MaterialRegistry();
        reg.put(unitCp());
        net.rainbowcreation.orge.material.ActiveMaterials.swap(
                new net.rainbowcreation.orge.material.ActiveMaterials.State(reg));
        SectionData data = mgr.store(DIM).get(key);
        for (int i = 0; i < SectionData.CELLS; i++) data.setMaterialAt(i, ORGE_UNIT);
        mgr.store(DIM).put(key, data);
    }

    /**
     * S6 (re-authored from the stale T15 T-as-E bridge): the DORMANT per-section {@link
     * MinecraftThermalWorld#writeBack} now ENCODES {@code E = m·h(T)} from the engine's derived Tout
     * (law §6 — never stores the raw T as E). With a cp=1 material E = m·T. Mass persistence is unchanged.
     */
    @Test
    void writeBackEncodesEnthalpyFromTemperatureAndPersistsMass(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);
        seedUnitCpSection(mgr, key);

        float[] temps = new float[SectionData.CELLS];
        Arrays.fill(temps, 350f);
        float[] mass = new float[SectionData.CELLS];
        Arrays.fill(mass, 1000f); // geometry mass (e.g. water's default_mass)
        StepTask task = new StepTask(key, new char[SectionData.CELLS], mass, temps, null);
        ThermalWorld.BatchEntry entry = new ThermalWorld.BatchEntry(DIM, key, task);

        world.writeBack(entry, new StepResult(temps, mass));

        SectionData data = mgr.store(DIM).get(key);
        assertEquals(1000f * 350f, data.enthalpyAt(0), 1e-1f, "E = m·cp·T encoded (NOT raw T-as-E)");
        assertEquals(1000f, data.massAt(0), 1e-4f, "engine mass must be persisted, not left at 0");
        assertEquals(1000f, data.massAt(SectionData.CELLS - 1), 1e-4f, "all cells carry their mass");
    }

    @Test
    void writeBackDemotesToUniformWhenEngineFlattensAllCells(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);
        seedUnitCpSection(mgr, key);

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
        assertEquals(1000f * 300f, data.enthalpyAt(0), 1e-1f, "uniform E = m·cp·T preserved through demote");
        assertEquals(1000f, data.massAt(0), 1e-4f, "uniform mass preserved through demote");
    }

    @Test
    void writeBackStaysFullWhenAGradientRemains(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);
        seedUnitCpSection(mgr, key);

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
        assertEquals(1000f * 350f, data.enthalpyAt(0), 1e-1f, "cell 0 E = m·cp·350");
        assertEquals(1000f * 300f, data.enthalpyAt(1), 1e-1f, "cell 1 E = m·cp·300");
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
     *  never the index-0 orge:vacuum sentinel. */
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
        assertEquals(ORGE_AIR, prior[E], "untouched air cell records orge:air (input), not orge:vacuum");
        assertNotEquals(ORGE_VACUUM, prior[E], "must not record the index-0 vacuum sentinel");
    }

    /**
     * The keystone-closing half (durable-material E2): writeBackColumn must PERSIST the engine's
     * output material into the SectionStore so next cycle E1 reads it as authoritative identity.
     * Engine outputs water at one cell (W) and leaves an untouched air cell (A, outMat==0, input air).
     * After a write-back cycle the store must hold orge:water at W and orge:air (NOT the index-0
     * orge:vacuum sentinel) at A.
     */
    @Test
    void writeBackColumnPersistsEngineOutputMaterialAsDurableIdentity(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        world.setLastColumnLutForTest(recordLut()); // vacuum=0, air=1, water=2

        int sectionY = 4;
        // Section-local cells: W gets water, the rest air (matIx). Engine output mirrors that but
        // deposits 0 (no output) at A so the effective-species fallback to input air is exercised.
        int wCell = ColumnSectionCodec.colIdx(3, sectionY, 5, 7);  // engine-output water cell
        int aCell = ColumnSectionCodec.colIdx(8, sectionY, 9, 2);  // untouched air cell

        char[] inMat = new char[RegionMarshaller.CHUNK_N];
        // Default 0 (vacuum) for the bulk of the empty column; air only in our test section so the
        // air fallback is unambiguous (a column of vacuum elsewhere is the realistic empty-world case).
        for (int z = 0; z < 16; z++) {
            for (int sy = 0; sy < 16; sy++) {
                for (int x = 0; x < 16; x++) {
                    inMat[ColumnSectionCodec.colIdx(x, sectionY, sy, z)] = AIR_IX;
                }
            }
        }
        inMat[wCell] = WATER_IX;

        float[] mass = new float[RegionMarshaller.CHUNK_N];
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 300f);
        mass[wCell] = 1000f;

        ColumnTask task = new ColumnTask(0, 0, inMat, mass, temp);
        ThermalWorld.ColumnEntry entry = new ThermalWorld.ColumnEntry(DIM, 0, 0, task);

        // Engine output: water survives at W with its species; A produces nothing (outMat==0) so the
        // write-back's effective rule falls back to the input air there, never the vacuum sentinel.
        char[] outMat = new char[RegionMarshaller.CHUNK_N];
        outMat[wCell] = WATER_IX;
        ColumnResult result = new ColumnResult(outMat, mass.clone(), temp.clone());

        world.writeBackColumn(entry, result);

        SectionStore store = mgr.store(DIM);
        assertEquals(ORGE_WATER, store.materialAt(0, 0, sectionY, 3 + 16 * 5 + 256 * 7),
                "engine-output water persisted as durable identity");
        assertEquals(ORGE_AIR, store.materialAt(0, 0, sectionY, 8 + 16 * 9 + 256 * 2),
                "untouched air cell persists orge:air (effective fallback to input)");
        assertNotEquals(ORGE_VACUUM, store.materialAt(0, 0, sectionY, 8 + 16 * 9 + 256 * 2),
                "untouched air must NOT persist the index-0 vacuum sentinel");
        assertTrue(store.get(new SubchunkKey(0, sectionY, 0)).hasMaterials(),
                "section now has a material layer so E1 reads it as authoritative");
    }

    /**
     * F2 durable-identity (spec Part 3): on a PLACE, {@code captureBlockChange} records the placed block's
     * first-touch material as the cell's DURABLE {@link SectionStore} identity AT ONCE — so the placement
     * persists against the next assemble even before the engine writes it back (the vanish race, now fixed
     * for every species). Driven here at the {@code recordDurableIdentity} seam against the REAL §5 store,
     * because the enclosing {@code captureBlockChange} bails at {@code server == null} and cannot be driven
     * headlessly. Asserts the placed water id lands in the store, and that the guards (no-store, unloaded
     * column, null material) are silent no-ops.
     */
    @Test
    void recordDurableIdentityPersistsPlacedSpeciesAtOnce(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        SectionStore store = mgr.store(DIM);

        int sectionY = 4;
        int sectionCell = 3 + 16 * 5 + 256 * 7; // section-local lx=3, ly=5, lz=7
        Material placedWater = fluid(ORGE_WATER, 1000f);

        // PLACE: the placed water's first-touch identity is recorded immediately.
        MinecraftThermalWorld.recordDurableIdentity(store, 0, 0, sectionY, sectionCell, placedWater);
        assertEquals(ORGE_WATER, store.materialAt(0, 0, sectionY, sectionCell),
                "placed water becomes the cell's durable identity at once (vanish-race fix)");

        // Guard: null material (non-ORGE block) is a silent no-op — the cell identity is untouched.
        int otherCell = 8 + 16 * 9 + 256 * 2;
        MinecraftThermalWorld.recordDurableIdentity(store, 0, 0, sectionY, otherCell, null);
        assertNotEquals(ORGE_WATER, store.materialAt(0, 0, sectionY, otherCell),
                "null live material records nothing");

        // Guard: a null store and an unloaded column are silent no-ops (no throw).
        MinecraftThermalWorld.recordDurableIdentity(null, 0, 0, sectionY, sectionCell, placedWater);
        MinecraftThermalWorld.recordDurableIdentity(store, 99, 99, sectionY, sectionCell, placedWater);
    }

    /**
     * G2 event-driven BREAK (spec durable-material Part 4): {@code captureBreak} turns the cell into durable
     * {@code orge:vacuum} — it stamps the cell's {@link SectionStore} identity to {@code orge:vacuum} AND
     * enqueues a removal intent (so the drain stomps the cell to the index-0 sentinel, mass 0, neighbours
     * flow in). Driven against the REAL §5 store (the enclosing wake path bails at {@code server == null}).
     * captureBreak does NOT read the world block, so it needs no live server — it is fully exercisable here.
     */
    @Test
    void captureBreakRecordsDurableVacuumAndQueuesRemovalIntent(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SectionStore store = mgr.store(DIM);

        // World coords inside the loaded column (0,0). sectionY = blockY>>4.
        int bx = 3, by = 5 + 16 * 4, bz = 7;   // sectionY = 4, ly=5
        int sectionY = 4;
        int lx = bx & 15, ly = by & 15, lz = bz & 15;
        int sectionCell = lx + 16 * ly + 256 * lz;
        int engineCell = ColumnSectionCodec.colIdx(lx, sectionY, ly, lz);

        // Pre-seed the cell with a solid identity so the break has something to remove.
        store.setMaterialAt(0, 0, sectionY, sectionCell, ORGE_WATER);
        assertEquals(ORGE_WATER, store.materialAt(0, 0, sectionY, sectionCell), "precondition: cell is water");

        world.captureBreak(DIM, bx, by, bz);

        // Durable vacuum identity persisted (assembler will keep it vacuum — no air re-seed).
        assertEquals(ORGE_VACUUM, store.materialAt(0, 0, sectionY, sectionCell),
                "break records durable orge:vacuum (not orge:air)");

        // A removal intent is queued at the right engine cell (drain stomps to index-0 sentinel, no injection).
        List<PendingInjections.Intent> intents = world.pendingInjections().peekColumn(DIM, 0, 0);
        assertEquals(1, intents.size(), "exactly one intent queued");
        PendingInjections.Intent in = intents.get(0);
        assertTrue(in.removal(), "the queued intent is a removal (break → vacuum)");
        assertEquals(engineCell, in.cell(), "removal targets the broken engine cell");
        assertEquals(ORGE_VACUUM, in.species(), "removal species is the vacuum sentinel");
        assertEquals(0f, in.mass(), 0f, "removal carries zero mass");
    }

    /**
     * S6 (re-authored from the stale T15-E velocity bridge — law §7): writeBackColumn stores EXTENSIVE
     * momentum {@code p = m·vOut}, NOT the raw engine velocity. With a finite mass the stored momentum is
     * {@code m·v}; non-finite velocity sanitises to 0 first (so {@code m·0 = 0}); a massless cell stores
     * momentum 0 ({@code m·v = 0}) no matter the velocity — no velocity-ghost on reload.
     */
    @Test
    void writeBackColumnStoresMomentumMassTimesVelocityNotRawVelocity(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        world.setLastColumnLutForTest(recordLut()); // vacuum=0, air=1, water=2

        int sectionY = 4;
        int velCell  = ColumnSectionCodec.colIdx(2, sectionY, 3, 4);   // finite mass + velocity
        int nanCell  = ColumnSectionCodec.colIdx(5, sectionY, 6, 7);   // finite mass, non-finite velocity
        int zeroMassCell = ColumnSectionCodec.colIdx(9, sectionY, 1, 2); // massless cell + velocity

        char[] inMat = new char[RegionMarshaller.CHUNK_N];
        Arrays.fill(inMat, AIR_IX);
        float[] mass = new float[RegionMarshaller.CHUNK_N];
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 300f);
        ColumnTask task = new ColumnTask(0, 0, inMat, mass.clone(), temp);
        ThermalWorld.ColumnEntry entry = new ThermalWorld.ColumnEntry(DIM, 0, 0, task);

        // Engine output: finite mass at velCell + nanCell, ZERO mass at zeroMassCell.
        char[] outMat = Arrays.copyOf(inMat, inMat.length);
        float[] outMass = new float[RegionMarshaller.CHUNK_N];
        outMass[velCell]  = 4f;       // p = 4·v
        outMass[nanCell]  = 10f;      // non-finite v sanitises to 0 ⇒ p = 0
        outMass[zeroMassCell] = 0f;   // massless ⇒ p = 0 regardless of v
        float[] outVx = new float[RegionMarshaller.CHUNK_N];
        float[] outVy = new float[RegionMarshaller.CHUNK_N];
        float[] outVz = new float[RegionMarshaller.CHUNK_N];
        outVx[velCell] = 2.5f; outVy[velCell] = -1.2f; outVz[velCell] = 0.8f;
        outVx[nanCell] = Float.NaN; outVy[nanCell] = Float.POSITIVE_INFINITY; outVz[nanCell] = Float.NEGATIVE_INFINITY;
        outVx[zeroMassCell] = 99f; outVy[zeroMassCell] = -50f; outVz[zeroMassCell] = 12f;
        ColumnResult result = new ColumnResult(outMat, outMass, temp.clone(), outVx, outVy, outVz);

        world.writeBackColumn(entry, result);

        int velSec  = 2 + 16 * 3 + 256 * 4;
        int nanSec  = 5 + 16 * 6 + 256 * 7;
        int zeroSec = 9 + 16 * 1 + 256 * 2;
        SectionData data = mgr.store(DIM).get(new SubchunkKey(0, sectionY, 0));
        assertNotNull(data, "section must exist after write-back");

        // p = m·v (extensive momentum), NOT raw velocity.
        assertEquals(4f * 2.5f,  data.momXAt(velSec), 1e-4f, "momX = m·vX (extensive momentum)");
        assertEquals(4f * -1.2f, data.momYAt(velSec), 1e-4f, "momY = m·vY");
        assertEquals(4f * 0.8f,  data.momZAt(velSec), 1e-4f, "momZ = m·vZ");

        assertEquals(0f, data.momXAt(nanSec), "NaN velX → 0 then ·mass = 0");
        assertEquals(0f, data.momYAt(nanSec), "+Inf velY → 0");
        assertEquals(0f, data.momZAt(nanSec), "-Inf velZ → 0");

        // The velocity-ghost guard: a massless cell stores ZERO momentum even with a large velocity
        // (m·v = 0; ±0.0f in float both qualify — use a delta so signed-zero passes).
        assertEquals(0f, data.momXAt(zeroSec), 0f, "massless cell ⇒ momentum 0 (m·v = 0), no velocity-ghost");
        assertEquals(0f, data.momYAt(zeroSec), 0f, "massless cell ⇒ momentum 0");
        assertEquals(0f, data.momZAt(zeroSec), 0f, "massless cell ⇒ momentum 0");
    }

    /**
     * S6 (a) — ENERGY across a latent plateau survives feed→writeback UNCLAMPED (law §6). The engine's
     * authoritative {@code eOut} (ColumnResult.enthalpy) is stored straight into the extensive E channel
     * with NO {@code [0,6000]} clamp. A mid-boil-plateau cell carries {@code E = m·(h(373)+L/2) ≫ 6000 J}
     * — a stray Kelvin-range clamp on E would crush it to 6000 and destroy the latent slug; this test
     * fails on that mistake and passes only when E is stored raw. Driven through {@code writeBackColumn}
     * (a stub/echo engine output) to isolate the Java WRITEBACK transform S6 owns.
     */
    @Test
    void writeBackColumnStoresEnthalpyUnclampedAcrossLatentPlateau(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        world.setLastColumnLutForTest(recordLut());

        int sectionY = 4;
        int cell = ColumnSectionCodec.colIdx(7, sectionY, 8, 9);
        int secCell = 7 + 16 * 8 + 256 * 9;

        // Mid-boil-plateau absolute E for water: E = m·(cp·T* + L/2), far above 6000 J.
        final float mass = 1000f, cp = 4186f, tStar = 373.15f, L = 2.256e6f;
        double midEta = (double) cp * tStar + (double) L / 2.0;       // ≈ 2.69e6 J/kg
        float storedE = (float) ((double) mass * midEta);             // ≈ 2.69e9 J — must survive intact

        char[] inMat = new char[RegionMarshaller.CHUNK_N];
        Arrays.fill(inMat, AIR_IX);
        inMat[cell] = WATER_IX;
        float[] m = new float[RegionMarshaller.CHUNK_N];
        m[cell] = mass;
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 300f);
        ColumnTask task = new ColumnTask(0, 0, inMat, m.clone(), temp);
        ThermalWorld.ColumnEntry entry = new ThermalWorld.ColumnEntry(DIM, 0, 0, task);

        // Engine ECHO output: same matIx/mass; eOut = storedE; tOut = a (legacy) derived diagnostic that
        // we deliberately set far BELOW the plateau (285 K) to prove the stored value is eOut, not tOut.
        char[] outMat = Arrays.copyOf(inMat, inMat.length);
        float[] z = new float[RegionMarshaller.CHUNK_N];
        float[] tOut = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(tOut, 300f);
        tOut[cell] = 285f;
        float[] eOut = new float[RegionMarshaller.CHUNK_N];
        eOut[cell] = storedE;
        ColumnResult result = new ColumnResult(outMat, m.clone(), tOut,
                z.clone(), z.clone(), z.clone(), z.clone(), new float[RegionMarshaller.CHUNK_N], eOut);

        world.writeBackColumn(entry, result);

        SectionData data = mgr.store(DIM).get(new SubchunkKey(0, sectionY, 0));
        assertNotNull(data);
        // The plateau E (≫ 6000) survives EXACTLY — no [0,6000] clamp, no T-as-E re-linearisation.
        assertEquals(storedE, data.enthalpyAt(secCell), 1e-3f * storedE,
                "stored E is the engine eOut UNCLAMPED (≈2.69e9 J); a [0,6000] clamp would crush it to 6000");
        assertTrue(data.enthalpyAt(secCell) > 6000f * 1000f,
                "E is far above the Kelvin clamp ceiling — proves no magnitude clamp was applied");
        assertNotEquals(285f, data.enthalpyAt(secCell),
                "stored value is eOut, NOT the legacy derived tOut (285 K) re-stored as E");
    }

    /**
     * S6 (c) — FEED tIn is DERIVED + CLAMPED, never the raw E. The engine's diagnostic temperature slot
     * for a mid-plateau cell carries the pinned plateau T (373 K), clamped to {@code [0,6000]} — NOT the
     * raw stored E (≈2.69e9 J, which a Kelvin clamp would saturate to 6000). This is the exact transform
     * {@code columnSource} applies per cell ({@code EnthalpyCurve.deriveT} → {@code clampDeriveBoundary}),
     * tested directly because {@code columnSource} needs a live ServerLevel.
     */
    @Test
    void feedTinIsDerivedAndClampedNotRawEnthalpy() {
        Material water = TestMaterials.waterWithLatent();
        MaterialRegistry reg = TestMaterials.registryOf(
                java.util.List.of(TestMaterials.voidMat(), water, TestMaterials.steam(), TestMaterials.air()));
        Function<Identifier, Material> lookup = id -> reg.get(id).orElse(null);

        final float mass = 1000f, cp = 4186f, tStar = 373.15f, L = 2.256e6f;
        double midEta = (double) cp * tStar + (double) L / 2.0;
        double storedE = (double) mass * midEta;                       // ≈ 2.69e9 J

        float derived = EnthalpyCurve.deriveT(storedE, mass, water, lookup, 285f);
        float tIn = MinecraftThermalWorld.clampDeriveBoundary(derived);

        // The cell is mid-boil-plateau ⇒ derived T pins at the boil threshold 373.15 K.
        assertEquals(tStar, tIn, 0.5f, "tIn is the DERIVED pinned plateau T (373 K), not raw E");
        assertTrue(tIn < 6000f, "tIn is a sane Kelvin, far below the clamp ceiling");
        assertNotEquals((float) storedE, tIn,
                "tIn is NOT the raw stored E (≈2.69e9), which a Kelvin clamp would saturate to 6000");

        // A genuinely-too-hot derive clamps to the [0,6000] derive boundary.
        assertEquals(6000f, MinecraftThermalWorld.clampDeriveBoundary(9999f), 0f, "over-ceiling T clamps to 6000");
        assertEquals(0f, MinecraftThermalWorld.clampDeriveBoundary(-5f), 0f, "below-floor T clamps to 0");
        assertEquals(0f, MinecraftThermalWorld.clampDeriveBoundary(Float.NaN), 0f, "non-finite T clamps to floor");
    }
}
