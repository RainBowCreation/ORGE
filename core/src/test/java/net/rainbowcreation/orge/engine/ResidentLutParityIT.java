package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ResidentLutParityIT {

    private static final int N = RegionMarshaller.CHUNK_N;

    private static boolean nativeAvailable() {
        try { NativeLoader.load(); return true; } catch (Throwable t) { return false; }
    }

    private static Material mat(String id, float hc, float k, float mol, float mn, float mx, float visc) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(hc).thermalConductivity(k).molarMass(mol)
                .minMass(mn).maxMass(mx).viscosity(visc)
                .defaultMass(0f).defaultTemperature(300f).build();
    }
    private static void put(char[] mi, float[] m, float[] t, int x, int y, int z, char ix, float mass, float temp) {
        int i = x + 16 * y + 6144 * z; mi[i] = ix; m[i] = mass; t[i] = temp;
    }
    /** A fresh zero array of N floats — the original 3-arg ColumnTask reset these channels each step. */
    private static float[] z() { return new float[N]; }

    private static List<Material> lut() {
        List<Material> lut = new ArrayList<>();
        lut.add(mat("orge:void",  0f,   0f,    0f,    0f,   0f,   Float.POSITIVE_INFINITY));
        lut.add(mat("orge:water", 4186f,0.6f,  0.018f,125f, 1000f,0f));
        lut.add(mat("orge:lava",  1000f,1.0f,  0.100f,200f, 2000f,5000f));
        lut.add(mat("orge:air",   1005f,0.025f,0.029f,1.0f, 50f,  0f));
        return lut;
    }
    /** Material-id → Material lookup over this test's own {@link #lut()} (null for unknowns), so the seed
     *  can encode absolute E exactly as production ({@code ColumnAssembler.assemble}) does. */
    private static Function<Identifier, Material> lookup() {
        Map<Identifier, Material> byId = new HashMap<>();
        for (Material m : lut()) byId.put(m.id(), m);
        return byId::get;
    }

    private static ColumnTask seedColumn() {
        char[] matIx = new char[N]; float[] mass = new float[N]; float[] tIn = new float[N];
        for (int i = 0; i < N; i++) { matIx[i] = 0; mass[i] = 0f; tIn[i] = 300f; }
        put(matIx, mass, tIn, 8, 40, 8, (char) 1, 1000f, 300f);
        put(matIx, mass, tIn, 8, 38, 8, (char) 3, 30f,   300f);
        put(matIx, mass, tIn, 8, 36, 8, (char) 2, 2000f, 1500f);
        // Task 2: absolute E [J] is the engine's thermal truth (law §7) — encode it from the seed
        // temperature, exactly as production does (ColumnAssembler.assemble). Empty/vacuum cells stay 0.
        List<Material> lut = lut();
        Function<Identifier, Material> lookup = lookup();
        float[] enth = new float[N];
        for (int i = 0; i < N; i++) {
            char ix = matIx[i];
            if (ix == 0 || mass[i] <= 0f) continue;
            Material m = lut.get(ix);
            enth[i] = (float) EnthalpyCurve.cellE(mass[i], m, lookup, tIn[i]);
        }
        float[] z = new float[N];
        return new ColumnTask(0, 0, matIx, mass, tIn,
                z.clone(), z.clone(), z.clone(), z.clone(), z.clone(), enth);
    }

    /**
     * Regression guard: an 8-step run must produce bit-identical output every time it runs.
     * Golden regenerated for Engine B (Stage-1) on 2026-06-05 switchover from Engine A;
     * regenerated again 2026-06-10 for the issue-#8 enthalpy-cargo advection (DECODE derives T
     * from E_new = E_snap + Σṁ·h instead of the float mass-weighted T mix — last-ULP T shifts
     * on moving cells).
     * Re-captured 2026-06-15 for the T10 .so rebuild: the golden was re-captured after the T4-T9
     * RESOLVE evolution (swap cadence / ρ_eff convection / released-PE→heat / gas density+eviction
     * fixes) shifted matIx[49672] (the only assertion that diverged). This is the documented
     * "regenerate the determinism golden when RESOLVE changes" case (00-MASTER-RULES §STALE TESTS),
     * NOT a wiring bug: the synthetic LUT here leaves the 5 new v4 §1.2 columns (ε / β / latentHeat /
     * T_ref_gas / + the new schema field) at 0, so T10's activation of those columns is itself
     * unaffected by — and contributes nothing to — this capture. Grand-mass conservation of the
     * 8-step result was verified before accepting (3030.000000 in vs 3029.999898 out, Δ≈-1e-4 kg
     * float noise; 12 occupied cells; temps [300.003, 1500.016] K; no NaN/Inf, no 0/6000K ghost).
     * The other two tests ({@code oldEpoch...}, {@code unknownEpoch...}) prove run-to-run determinism
     * independently, making this capture stable.
     * Re-captured 2026-06-15 (Task 2 — absolute-E seed): the seed now encodes absolute E [J] explicitly
     * via {@code EnthalpyCurve.cellE} (law §7) and the 8-step loop threads the engine's returned E forward
     * as the next step's enthalpy; the engine now TRUSTS that stored E (the {@code mass·cp·T} reconstruction
     * was deleted in {@code d644f48}), so the derived-T and the E-cargo advection that depends on it shift
     * by last-ULP. Exactly 10 cells diverged, all last-ULP: matIx unchanged on every one (no relabel —
     * 49688/49720/49736 stay lava=2, 49752/49768 stay air=3, 49784/49800/49816/49832/49848 stay water=1);
     * mass deltas ≤ 6.1e-5 kg (49688 295.87927→295.8793, 49720 331.54056→331.5405, 49736 819.6947→819.69476,
     * the rest 0); temp deltas ≤ 3.7e-4 K. Grand-mass conservation verified before accepting: 3030.000000 in
     * vs 3029.999929 out (Δ≈-7.1e-5 kg float noise); 12 occupied cells; temps [300.0034, 1500.0162] K; no
     * NaN/Inf, no 0/6000K ghost. Documented "regenerate the determinism golden when RESOLVE changes" case
     * (00-MASTER-RULES §STALE TESTS), NOT a wiring bug.
     */
    @Test
    void registerOnceRunsBitIdenticalToGolden() throws Exception {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        OrgeEngine engine = new NativeEngine();
        engine.registerMaterials(7, lut());

        List<ColumnTask> cols = List.of(seedColumn());
        ColumnResult r = null;
        for (int s = 0; s < 8; s++) {
            r = engine.stepWorld(cols, 7, 0.25,
                    OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);
            // Carry the engine's returned absolute E (eOut) forward as next step's enthalpy (law §7 truth);
            // everything else (velX/velY/velZ/p/swapReady) is RESET to zero each step exactly like the
            // original 3-arg ColumnTask — the only delta from the original test is the enthalpy channel.
            cols = List.of(new ColumnTask(0, 0, r.matIx(), r.mass(), r.temperature(),
                    z(), z(), z(), z(), z(), r.enthalpy()));
        }

        try (InputStream in = getClass().getResourceAsStream("/golden/resident-lut-step.bin");
             DataInputStream g = new DataInputStream(in)) {
            assertNotNull(in, "golden resource present");
            assertEquals(N, g.readInt(), "golden cell count");
            for (int i = 0; i < N; i++) assertEquals(g.readChar(),  r.matIx()[i],       "matIx[" + i + "]");
            for (int i = 0; i < N; i++) assertEquals(g.readFloat(), r.mass()[i],   0f,   "mass["  + i + "]");
            for (int i = 0; i < N; i++) assertEquals(g.readFloat(), r.temperature()[i], 0f, "temp[" + i + "]");
        }
    }

    @Test
    void oldEpochBatchStillStepsUnderItsTableWhileNewEpochIsLive() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        OrgeEngine engine = new NativeEngine();
        engine.registerMaterials(1, lut());
        List<Material> lut2 = lut();
        lut2.set(1, mat("orge:water", 4186f, 0.6f, 0.018f, 125f, 1000f, 9000f));
        engine.registerMaterials(2, lut2);

        ColumnResult underEpoch1 = engine.stepWorld(List.of(seedColumn()), 1, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);
        ColumnResult underEpoch1Again = engine.stepWorld(List.of(seedColumn()), 1, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);
        for (int i = 0; i < N; i++) {
            assertEquals(underEpoch1.matIx()[i], underEpoch1Again.matIx()[i], "epoch-1 matIx stable @" + i);
            assertEquals(underEpoch1.mass()[i],  underEpoch1Again.mass()[i], 0f, "epoch-1 mass stable @" + i);
        }
    }

    @Test
    void unknownEpochIsSafeNoOp() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        OrgeEngine engine = new NativeEngine();
        ColumnTask seed = seedColumn();
        ColumnResult r = engine.stepWorld(List.of(seed), 999, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);
        for (int i = 0; i < N; i++) {
            assertEquals(seed.matIx()[i], r.matIx()[i], "no-op matIx pass-through @" + i);
            assertEquals(seed.mass()[i],  r.mass()[i], 0f, "no-op mass pass-through @" + i);
            assertEquals(seed.temperature()[i], r.temperature()[i], 0f, "no-op temp pass-through @" + i);
        }
    }

}
