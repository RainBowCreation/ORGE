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

// F2-S5 (re-authored from the stale velocity-channel names — spec §1.3 RETIRES transported vx/vy/vz; the
// conserved carrier across the JNI/assembler ABI is EXTENSIVE momentum p [kg·m/s], law §7/§1.1). The
// channel formerly named velX/velY/velZ is now momX/momY/momZ; these tests assert that the momentum channel
// threads through the assembler + native round-trip and stays finite. (@Tag integration: requires liborge.)
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
    void stepRoundTripsMomentumFinite() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        engine.registerMaterials(102, lutWithWater());
        // one column: a water cell on a stone floor; after one advection step the EXTENSIVE momentum OUT
        // channel (law §7/§1.1; §1.3 retires transported velocity) is populated and FINITE — proves the
        // momentum arrays round-trip through the JNI ABI.
        int N = RegionMarshaller.CHUNK_N;
        char[] mat = new char[N]; float[] mass = new float[N]; float[] t = new float[N];
        for (int i=0;i<N;i++){ mat[i]=0; mass[i]=0f; t[i]=300f; }
        int floor = 8 + 16*39 + 6144*8, cell = 8 + 16*40 + 6144*8;
        mat[floor]=(char)0; // (kept void/empty floor is fine; we only need the engine to run + write momentum)
        mat[cell]=(char)1; mass[cell]=1000f; t[cell]=290f;
        ColumnTask col = new ColumnTask(0,0, mat, mass, t);
        ColumnResult r = engine.stepWorld(java.util.List.of(col), 102, 0.25, OrgeEngine.PASS_ADVECTION).get(0);
        assertTrue(Float.isFinite(r.momX()[cell]), "momentum-out finite after step (§7)");
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
    // Task 15: assembler reads MOMENTUM from SectionCells into ColumnTask (law §7; §1.3 retires velocity)
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
     * T15-A (re-authored, §1.3/§7): EXTENSIVE momentum p [kg·m/s] stored in a SectionCells is scattered by
     * assemble() into ColumnTask.momX/Y/Z at the correct engine index (x + 16*y + 6144*z). The transported
     * velocity symbol is RETIRED (§1.3) — the channel carries momentum.
     */
    @Test
    void assembleScattersMomentumFromSectionCellsIntoColumnTask() {
        MaterialLut lut = makeLut();
        MaterialRegistry reg = makeRegistry();

        int testSectionY = 4;
        int testX = 3, testSy = 5, testZ = 7;
        int sectionIdx = testX + 16 * testSy + 256 * testZ;
        int engineY = testSectionY * 16 + testSy + 64;
        int expectedEngineIdx = testX + 16 * engineY + 6144 * testZ;

        float expectedPx = 1.5f, expectedPy = -0.7f, expectedPz = 0.3f;

        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            float[] px = new float[4096];
            float[] py = new float[4096];
            float[] pz = new float[4096];
            Arrays.fill(temp, 300f);
            if (sectionY == testSectionY) {
                px[sectionIdx] = expectedPx;
                py[sectionIdx] = expectedPy;
                pz[sectionIdx] = expectedPz;
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, new char[4096], new net.minecraft.resources.Identifier[4096], px, py, pz);
        };

        ColumnTask task = ColumnAssembler.assemble(0, 0, lut, reg, src);

        assertEquals(expectedPx, task.momX()[expectedEngineIdx], 1e-6f,
                "momX scattered to correct engine index");
        assertEquals(expectedPy, task.momY()[expectedEngineIdx], 1e-6f,
                "momY scattered to correct engine index");
        assertEquals(expectedPz, task.momZ()[expectedEngineIdx], 1e-6f,
                "momZ scattered to correct engine index");
        // Cells not set must be zero.
        assertEquals(0f, task.momX()[0], "unset momX must be zero");
    }

    /**
     * T15-B (re-authored, §1.3/§7): SectionCells back-compat constructors (3-arg, 4-arg, 5-arg) produce zero
     * MOMENTUM so existing callers that don't supply momentum still assemble a ColumnTask with zero momX/Y/Z.
     */
    @Test
    void assembleBackCompatConstructorsProduceZeroMomentum() {
        MaterialLut lut = makeLut();
        MaterialRegistry reg = makeRegistry();

        // 3-arg back-compat: mat/mass/temp only (no priorSpecies, no storedMaterial, no momentum).
        ColumnAssembler.SectionSource src3arg = (cx, cz, sectionY) ->
                new ColumnAssembler.SectionCells(new char[4096], new float[4096], new float[4096]);

        ColumnTask t3 = ColumnAssembler.assemble(0, 0, lut, reg, src3arg);
        for (int i = 0; i < RegionMarshaller.CHUNK_N; i++) {
            if (t3.momX()[i] != 0f || t3.momY()[i] != 0f || t3.momZ()[i] != 0f) {
                fail("Back-compat 3-arg: momentum must be all zeros but cell " + i + " is non-zero");
            }
        }

        // 4-arg back-compat: mat/mass/temp/priorSpecies.
        ColumnAssembler.SectionSource src4arg = (cx, cz, sectionY) ->
                new ColumnAssembler.SectionCells(new char[4096], new float[4096], new float[4096], new char[4096]);

        ColumnTask t4 = ColumnAssembler.assemble(0, 0, lut, reg, src4arg);
        for (int i = 0; i < RegionMarshaller.CHUNK_N; i++) {
            if (t4.momX()[i] != 0f || t4.momY()[i] != 0f || t4.momZ()[i] != 0f) {
                fail("Back-compat 4-arg: momentum must be all zeros but cell " + i + " is non-zero");
            }
        }
    }

    /**
     * T15-C: the shared finite-or-0 sanitizer (StepValidator.cleanVelocity — the production method the
     * momentum writeback's cleanMomentum delegates to) maps NaN/±Inf to 0 (null fallback path), and passes
     * finite values through unchanged. This is a numeric-sanitizer test, NOT a transported-velocity assertion.
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
