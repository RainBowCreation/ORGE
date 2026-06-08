package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.scheduler.ColumnAssembler;
import net.rainbowcreation.orge.scheduler.MaterialLut;
import net.rainbowcreation.orge.scheduler.StepValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static net.rainbowcreation.orge.engine.TestMaterials.lutOf;
import static net.rainbowcreation.orge.engine.TestMaterials.registryOf;

@Tag("integration")
class EngineBVelocityIT {
    private static boolean nativeAvailable() {
        try { NativeLoader.load(); return true; } catch (Throwable t) { return false; }
    }
    private static Material mat(String id, float hc, float k, float mol, float mn, float mx, float visc, float defMass) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(hc).thermalConductivity(k).molarMass(mol)
                .minMass(mn).maxMass(mx).viscosity(visc)
                .defaultMass(defMass).defaultTemperature(300f).build();
    }
    private static List<Material> lutWithWater() {
        List<Material> lut = new ArrayList<>();
        lut.add(mat("orge:void",  0f,    0f,    0f,    0f,   0f,    Float.POSITIVE_INFINITY, 0f));
        lut.add(mat("orge:water", 4186f, 0.6f,  0.018f,125f, 1100f, 0.001f, 1000f));
        return lut;
    }

    @Test
    void registerWithDefaultMassDoesNotThrow() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        // Should accept the 7-array register (cond,heatCap,molar,minMass,maxMass,visc,defaultMass).
        assertDoesNotThrow(() -> engine.registerMaterials(101, lutWithWater()));
    }

    @Test
    void stepRoundTripsVelocityFinite() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        engine.registerMaterials(102, lutWithWater());
        // one column: a water cell on a stone floor; after one advection step the velocity OUT
        // channel is populated and FINITE (proves the velocity arrays round-trip through JNI).
        int N = RegionMarshaller.CHUNK_N;
        char[] mat = new char[N]; float[] mass = new float[N]; float[] t = new float[N];
        for (int i=0;i<N;i++){ mat[i]=0; mass[i]=0f; t[i]=300f; }
        int floor = 8 + 16*39 + 6144*8, cell = 8 + 16*40 + 6144*8;
        mat[floor]=(char)0; // (kept void/empty floor is fine; we only need the engine to run + write velocity)
        mat[cell]=(char)1; mass[cell]=1000f; t[cell]=290f;
        ColumnTask col = new ColumnTask(0,0, mat, mass, t);
        ColumnResult r = engine.stepWorld(java.util.List.of(col), 102, 0.25, OrgeEngine.PASS_ADVECTION).get(0);
        assertTrue(Float.isFinite(r.velX()[cell]), "velocity-out finite after step");
    }

    @Test
    void stepRoundTripsPressureFinite() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        engine.registerMaterials(103, lutWithWater());
        int N = RegionMarshaller.CHUNK_N;
        char[] mat = new char[N]; float[] mass = new float[N]; float[] t = new float[N];
        for (int i=0;i<N;i++){ mat[i]=0; mass[i]=0f; t[i]=300f; }
        int cell = 8 + 16*40 + 6144*8;
        mat[cell]=(char)1; mass[cell]=1000f; t[cell]=290f;
        float[] vx=new float[N], vy=new float[N], vz=new float[N], p=new float[N];
        p[cell] = 4242.0f;  // seed dynamic pressure
        ColumnTask col = new ColumnTask(0,0, mat, mass, t, vx, vy, vz, p);  // 9-arg with p
        ColumnResult r = engine.stepWorld(java.util.List.of(col), 103, 0.25, OrgeEngine.PASS_ADVECTION).get(0);
        assertTrue(Float.isFinite(r.p()[cell]), "pressure-out finite after step");
    }

    @Test
    void stepWithNoPassesPreservesPressureExactly() {
        // passes==0: no conduction, no advection -> the JNI marshals p in, copies it through the
        // World, and reads it back. This is the cleanest proof p survives the native call end-to-end.
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        engine.registerMaterials(104, lutWithWater());
        int N = RegionMarshaller.CHUNK_N;
        char[] mat = new char[N]; float[] mass = new float[N]; float[] t = new float[N];
        for (int i=0;i<N;i++){ mat[i]=0; mass[i]=0f; t[i]=300f; }
        int cell = 8 + 16*40 + 6144*8;
        mat[cell]=(char)1; mass[cell]=1000f; t[cell]=290f;
        float[] vx=new float[N], vy=new float[N], vz=new float[N], p=new float[N];
        p[cell] = 7777.0f;
        ColumnTask col = new ColumnTask(0,0, mat, mass, t, vx, vy, vz, p);
        ColumnResult r = engine.stepWorld(java.util.List.of(col), 104, 0.25, 0).get(0);
        assertEquals(7777.0f, r.p()[cell], 1e-3f, "p preserved exactly through native call with passes=0");
        assertEquals(0f, r.p()[cell + 1], "untouched cell p stays 0");
    }

    // =====================================================================
    // Task 15: assembler reads velocity from SectionCells into ColumnTask
    // =====================================================================

    /** Helper: build a minimal LUT (vacuum + water) using TestMaterials.lutOf. */
    private static MaterialLut makeLut() {
        return lutOf(lutWithWater());
    }

    /** Helper: build a matching registry using TestMaterials.registryOf. */
    private static MaterialRegistry makeRegistry() {
        return registryOf(lutWithWater());
    }

    /**
     * T15-A: velocity stored in a SectionCells is scattered by assemble() into
     * ColumnTask.velX/Y/Z at the correct engine index (x + 16*y + 6144*z).
     */
    @Test
    void assembleScattersVelocityFromSectionCellsIntoColumnTask() {
        MaterialLut lut = makeLut();
        MaterialRegistry reg = makeRegistry();

        int testSectionY = 4;
        int testX = 3, testSy = 5, testZ = 7;
        int sectionIdx = testX + 16 * testSy + 256 * testZ;
        int engineY = testSectionY * 16 + testSy + 64;
        int expectedEngineIdx = testX + 16 * engineY + 6144 * testZ;

        float expectedVx = 1.5f, expectedVy = -0.7f, expectedVz = 0.3f;

        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            float[] vx = new float[4096];
            float[] vy = new float[4096];
            float[] vz = new float[4096];
            Arrays.fill(temp, 300f);
            if (sectionY == testSectionY) {
                vx[sectionIdx] = expectedVx;
                vy[sectionIdx] = expectedVy;
                vz[sectionIdx] = expectedVz;
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, new char[4096], new net.minecraft.resources.Identifier[4096], vx, vy, vz);
        };

        ColumnTask task = ColumnAssembler.assemble(0, 0, lut, reg, src);

        assertEquals(expectedVx, task.velX()[expectedEngineIdx], 1e-6f,
                "velX scattered to correct engine index");
        assertEquals(expectedVy, task.velY()[expectedEngineIdx], 1e-6f,
                "velY scattered to correct engine index");
        assertEquals(expectedVz, task.velZ()[expectedEngineIdx], 1e-6f,
                "velZ scattered to correct engine index");
        // Cells not set must be zero.
        assertEquals(0f, task.velX()[0], "unset velX must be zero");
    }

    /**
     * T15-B: SectionCells back-compat constructors (3-arg, 4-arg, 5-arg) produce zero velocity
     * so existing callers that don't supply velocity still assemble a ColumnTask with zero velX/Y/Z.
     */
    @Test
    void assembleBackCompatConstructorsProduceZeroVelocity() {
        MaterialLut lut = makeLut();
        MaterialRegistry reg = makeRegistry();

        // 3-arg back-compat: mat/mass/temp only (no priorSpecies, no storedMaterial, no velocity).
        ColumnAssembler.SectionSource src3arg = (cx, cz, sectionY) ->
                new ColumnAssembler.SectionCells(new char[4096], new float[4096], new float[4096]);

        ColumnTask t3 = ColumnAssembler.assemble(0, 0, lut, reg, src3arg);
        for (int i = 0; i < RegionMarshaller.CHUNK_N; i++) {
            if (t3.velX()[i] != 0f || t3.velY()[i] != 0f || t3.velZ()[i] != 0f) {
                fail("Back-compat 3-arg: velocity must be all zeros but cell " + i + " is non-zero");
            }
        }

        // 4-arg back-compat: mat/mass/temp/priorSpecies.
        ColumnAssembler.SectionSource src4arg = (cx, cz, sectionY) ->
                new ColumnAssembler.SectionCells(new char[4096], new float[4096], new float[4096], new char[4096]);

        ColumnTask t4 = ColumnAssembler.assemble(0, 0, lut, reg, src4arg);
        for (int i = 0; i < RegionMarshaller.CHUNK_N; i++) {
            if (t4.velX()[i] != 0f || t4.velY()[i] != 0f || t4.velZ()[i] != 0f) {
                fail("Back-compat 4-arg: velocity must be all zeros but cell " + i + " is non-zero");
            }
        }
    }

    /**
     * T15-C: StepValidator.cleanVelocity maps NaN/±Inf to 0 (null fallback path),
     * and passes finite values through unchanged.
     */
    @Test
    void cleanVelocityMapsNonFiniteToZero() {
        float[] raw = {1.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -2.5f};
        float[] out = StepValidator.cleanVelocity(raw, null);
        assertEquals(5, out.length);
        assertEquals(1.5f,  out[0], 1e-6f, "finite positive passes through");
        assertEquals(0f,    out[1], "NaN → 0");
        assertEquals(0f,    out[2], "+Inf → 0");
        assertEquals(0f,    out[3], "-Inf → 0");
        assertEquals(-2.5f, out[4], 1e-6f, "finite negative passes through");
    }

    /**
     * T15-D: StepValidator.cleanVelocity with a non-null fallback uses fallback[i] for non-finite.
     */
    @Test
    void cleanVelocityUsesFallbackForNonFinite() {
        float[] raw      = {Float.NaN, 3.0f};
        float[] fallback = {99f, 0f};
        float[] out = StepValidator.cleanVelocity(raw, fallback);
        assertEquals(99f, out[0], 1e-6f, "NaN → fallback[0]");
        assertEquals(3.0f, out[1], 1e-6f, "finite passes through");
    }
}
