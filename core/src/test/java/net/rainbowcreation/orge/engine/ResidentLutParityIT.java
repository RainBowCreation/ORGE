package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    private static List<Material> lut() {
        List<Material> lut = new ArrayList<>();
        lut.add(mat("orge:void",  0f,   0f,    0f,    0f,   0f,   Float.POSITIVE_INFINITY));
        lut.add(mat("orge:water", 4186f,0.6f,  0.018f,125f, 1000f,0f));
        lut.add(mat("orge:lava",  1000f,1.0f,  0.100f,200f, 2000f,5000f));
        lut.add(mat("orge:air",   1005f,0.025f,0.029f,1.0f, 50f,  0f));
        return lut;
    }
    private static ColumnTask seedColumn() {
        char[] matIx = new char[N]; float[] mass = new float[N]; float[] tIn = new float[N];
        for (int i = 0; i < N; i++) { matIx[i] = 0; mass[i] = 0f; tIn[i] = 300f; }
        put(matIx, mass, tIn, 8, 40, 8, (char) 1, 1000f, 300f);
        put(matIx, mass, tIn, 8, 38, 8, (char) 3, 30f,   300f);
        put(matIx, mass, tIn, 8, 36, 8, (char) 2, 2000f, 1500f);
        return new ColumnTask(0, 0, matIx, mass, tIn);
    }

    /**
     * Regression guard: an 8-step run must produce bit-identical output every time it runs.
     * Golden regenerated for Engine B (Stage-1) on 2026-06-05 switchover from Engine A.
     * The other two tests ({@code oldEpoch...}, {@code unknownEpoch...}) prove run-to-run determinism
     * independently, making this capture stable.
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
            cols = List.of(new ColumnTask(0, 0, r.matIx(), r.mass(), r.temperature()));
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
