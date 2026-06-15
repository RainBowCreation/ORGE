package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
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
 * F2 / S1 RED — the law-#7 velocity-ghost at the LIVE tick seam.
 *
 * <p>POLICY (i): {@code writeBackColumn} must store the engine's AUTHORITATIVE extensive momentum
 * {@code result.momentum()} UNCHANGED on a mass clamp — exactly like enthalpy E (sanitize non-finite → 0
 * only; NO clamp, NO {@code cleanM·vOut} rescale). The pre-fix code instead rebuilds momentum from
 * {@code p_stored = cleanM · (p_engine / m_engine)} (MinecraftThermalWorld.java ~805-813), so when
 * {@link StepValidator#cleanMass} clamps the output mass ({@code cleanM ≠ m_engine}) the stored momentum is
 * silently rescaled — {@code p_stored ≠ p_engine}. That is the divergence this test pins.</p>
 *
 * <p><b>POST-FIX API:</b> these tests are authored against the renamed momentum channel
 * ({@code ColumnResult.momX/momY/momZ} carrying EXTENSIVE momentum [kg·m/s]). The S2/S3 rename has not
 * landed yet, so this file will NOT COMPILE until then — that is the intended TDD RED. The GREEN gate runs
 * after S3/S4, when the engine stores {@code result.momentum()} directly.</p>
 */
class MomentumRoundTripMassClampIT {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier ORGE_AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ORGE_WATER = Identifier.fromNamespaceAndPath("orge", "water");

    private static final char AIR_IX = 1;
    private static final char WATER_IX = 2;

    private static Material fluid(Identifier id, float defaultMass) {
        return Material.builder(id)
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(defaultMass).defaultTemperature(Float.NaN)
                .viscosity(0f)
                .build();
    }

    private static Material nonFluid(Identifier id, float defaultMass) {
        return Material.builder(id)
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(defaultMass).defaultTemperature(Float.NaN)
                .build();
    }

    /** LUT: vacuum=0, air=1, water=2. */
    private static List<Material> recordLut() {
        return List.of(MaterialLut.VACUUM, nonFluid(ORGE_AIR, 1.2f), fluid(ORGE_WATER, 1000f));
    }

    private static SectionStoreManager loadedManager(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0);
        return mgr;
    }

    /**
     * The headline bug. A cell carries engine momentum {@code p_engine} alongside an output mass the
     * §9 {@link StepValidator#cleanMass} clamps to {@code cleanM < m_engine} (here a NEGATIVE engine mass,
     * which {@code cleanMass} maps to 0 — the present, real divergence given
     * {@code fullMassBound()==Float.MAX_VALUE}). POLICY (i): the stored momentum must be {@code p_engine}
     * EXACTLY — never the {@code cleanM·(p_engine/m_engine)} rescale (= 0 here) the bug produces, and never
     * the {@code cleanM·(p_old/m_old)} rescale either. This is the assertion that FAILS at base 4bedc1e.
     */
    @Test
    void massClampDoesNotRescaleStoredMomentum(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        world.setLastColumnLutForTest(recordLut());

        int sectionY = 4;
        int clampCell = ColumnSectionCodec.colIdx(2, sectionY, 3, 4);
        int clampSec  = 2 + 16 * 3 + 256 * 4;
        SubchunkKey key = new SubchunkKey(0, sectionY, 0);

        // Pre-seed the stored OLD state (p_old, m_old) so we can assert the bug's other rescale variant too.
        final float pOldX = 7.0f, pOldY = -3.0f, pOldZ = 11.0f;
        final float mOld   = 500f;
        SectionData seed = mgr.store(DIM).get(key);
        seed.setMass(clampSec, mOld);
        seed.setMomentum(clampSec, pOldX, pOldY, pOldZ);
        mgr.store(DIM).put(key, seed);

        char[] inMat = new char[RegionMarshaller.CHUNK_N];
        Arrays.fill(inMat, AIR_IX);
        inMat[clampCell] = WATER_IX;
        float[] mass = new float[RegionMarshaller.CHUNK_N];
        mass[clampCell] = mOld;
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 300f);
        ColumnTask task = new ColumnTask(0, 0, inMat, mass.clone(), temp);
        ThermalWorld.ColumnEntry entry = new ThermalWorld.ColumnEntry(DIM, 0, 0, task);

        // Engine output for clampCell: a definite engine mass m_engine and engine momentum p_engine, but
        // an OUTPUT mass that cleanMass clamps. A negative engine mass → cleanM = 0 (cleanM ≠ m_engine).
        final float mEngine = 200f;       // the engine's own (pre-clean) mass for the cell
        final float pEngX = 123.0f, pEngY = -45.0f, pEngZ = 67.0f;   // engine extensive momentum [kg·m/s]
        char[] outMat = Arrays.copyOf(inMat, inMat.length);
        float[] outMass = new float[RegionMarshaller.CHUNK_N];
        outMass[clampCell] = -1f;         // cleanMass maps <0 → 0, so cleanM = 0 < m_engine (forces clamp)

        // POST-FIX channels: momX/momY/momZ carry EXTENSIVE momentum (the renamed velX/velY/velZ slots).
        float[] momX = new float[RegionMarshaller.CHUNK_N];
        float[] momY = new float[RegionMarshaller.CHUNK_N];
        float[] momZ = new float[RegionMarshaller.CHUNK_N];
        momX[clampCell] = pEngX; momY[clampCell] = pEngY; momZ[clampCell] = pEngZ;
        float[] z = new float[RegionMarshaller.CHUNK_N];
        ColumnResult result = new ColumnResult(outMat, outMass, temp.clone(),
                momX, momY, momZ, z.clone(), new float[RegionMarshaller.CHUNK_N],
                new float[RegionMarshaller.CHUNK_N]);

        world.writeBackColumn(entry, result);

        SectionData data = mgr.store(DIM).get(key);
        assertNotNull(data);

        // POLICY (i): stored momentum == engine momentum, EXACTLY (no clamp, no rescale).
        assertEquals(pEngX, data.momXAt(clampSec), 1e-4f, "stored momX == engine p (policy (i), unchanged)");
        assertEquals(pEngY, data.momYAt(clampSec), 1e-4f, "stored momY == engine p");
        assertEquals(pEngZ, data.momZAt(clampSec), 1e-4f, "stored momZ == engine p");

        // The bug rescales by cleanM/m: with cleanM=0 that is 0 — explicitly NOT what we stored.
        float rescaledFromEngine = 0f /* cleanM(=0) · (pEngX / mEngine) */;
        assertNotEquals(rescaledFromEngine, data.momXAt(clampSec),
                "must NOT be cleanM·(p_engine/m_engine) — the velocity-ghost rescale (=0 here)");
        // Nor the old-state rescale cleanM·(p_old/m_old) = 0 either.
        float rescaledFromOld = 0f /* cleanM(=0) · (pOldX / mOld) */;
        assertNotEquals(rescaledFromOld, data.momXAt(clampSec),
                "must NOT be cleanM·(p_old/m_old) rescale");

        // Sanity: the mass WAS clamped (cleanM = 0 ≠ m_engine), so the divergence really fired.
        assertEquals(0f, data.massAt(clampSec), 1e-4f, "output mass clamped to 0 (cleanM ≠ m_engine)");
    }

    /** Quiescent cell: engine momentum 0 with finite mass stays exactly 0 (no ghost manufactured). */
    @Test
    void quiescentCellStaysZeroMomentum(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        world.setLastColumnLutForTest(recordLut());

        int sectionY = 4;
        int cell = ColumnSectionCodec.colIdx(5, sectionY, 6, 7);
        int sec  = 5 + 16 * 6 + 256 * 7;

        char[] inMat = new char[RegionMarshaller.CHUNK_N];
        Arrays.fill(inMat, AIR_IX);
        inMat[cell] = WATER_IX;
        float[] mass = new float[RegionMarshaller.CHUNK_N];
        mass[cell] = 1000f;
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 300f);
        ColumnTask task = new ColumnTask(0, 0, inMat, mass.clone(), temp);
        ThermalWorld.ColumnEntry entry = new ThermalWorld.ColumnEntry(DIM, 0, 0, task);

        char[] outMat = Arrays.copyOf(inMat, inMat.length);
        float[] outMass = new float[RegionMarshaller.CHUNK_N];
        outMass[cell] = 1000f;            // unclamped, finite
        float[] z = new float[RegionMarshaller.CHUNK_N];
        ColumnResult result = new ColumnResult(outMat, outMass, temp.clone(),
                z.clone(), z.clone(), z.clone(), z.clone(),
                new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N]);

        world.writeBackColumn(entry, result);

        SectionData data = mgr.store(DIM).get(new SubchunkKey(0, sectionY, 0));
        assertNotNull(data);
        assertEquals(0f, data.momXAt(sec), 0f, "quiescent ⇒ stored momentum 0");
        assertEquals(0f, data.momYAt(sec), 0f, "quiescent ⇒ stored momentum 0");
        assertEquals(0f, data.momZAt(sec), 0f, "quiescent ⇒ stored momentum 0");
    }

    /** Non-finite engine momentum (NaN/±Inf) sanitizes to 0 (exactly like enthalpy E), regardless of mass. */
    @Test
    void nonFiniteEngineMomentumSanitisesToZero(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        world.setLastColumnLutForTest(recordLut());

        int sectionY = 4;
        int cell = ColumnSectionCodec.colIdx(9, sectionY, 1, 2);
        int sec  = 9 + 16 * 1 + 256 * 2;

        char[] inMat = new char[RegionMarshaller.CHUNK_N];
        Arrays.fill(inMat, AIR_IX);
        inMat[cell] = WATER_IX;
        float[] mass = new float[RegionMarshaller.CHUNK_N];
        mass[cell] = 1000f;
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 300f);
        ColumnTask task = new ColumnTask(0, 0, inMat, mass.clone(), temp);
        ThermalWorld.ColumnEntry entry = new ThermalWorld.ColumnEntry(DIM, 0, 0, task);

        char[] outMat = Arrays.copyOf(inMat, inMat.length);
        float[] outMass = new float[RegionMarshaller.CHUNK_N];
        outMass[cell] = 1000f;            // finite mass — so the zeroing is from the momentum sanitize alone
        float[] momX = new float[RegionMarshaller.CHUNK_N];
        float[] momY = new float[RegionMarshaller.CHUNK_N];
        float[] momZ = new float[RegionMarshaller.CHUNK_N];
        momX[cell] = Float.NaN;
        momY[cell] = Float.POSITIVE_INFINITY;
        momZ[cell] = Float.NEGATIVE_INFINITY;
        float[] z = new float[RegionMarshaller.CHUNK_N];
        ColumnResult result = new ColumnResult(outMat, outMass, temp.clone(),
                momX, momY, momZ, z.clone(),
                new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N]);

        world.writeBackColumn(entry, result);

        SectionData data = mgr.store(DIM).get(new SubchunkKey(0, sectionY, 0));
        assertNotNull(data);
        assertEquals(0f, data.momXAt(sec), "NaN momentum → 0 (non-finite sanitize, like E)");
        assertEquals(0f, data.momYAt(sec), "+Inf momentum → 0");
        assertEquals(0f, data.momZAt(sec), "-Inf momentum → 0");
    }
}
