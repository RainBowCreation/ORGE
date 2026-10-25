package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #44 — the JNI seam is energy-exact (law §6), proven against the real {@code liborge}.
 *
 * <p>The engine World is rebuilt on every {@code orgeStepWorld} call, so whatever precision E crosses the
 * seam at, every cell's E is rounded to it once per call. At float32 a 2000 kg stone cell (E ≈ 4.9e8 J)
 * has a 32 J ULP, and a per-call face inflow from a warm air pocket is a few J — it rounded away every
 * call while the air donor still paid. The seam now carries E at double, and the per-call World's closing
 * ledgers come back through {@link RegionStepResult#books()}.</p>
 *
 * <p>Scene: a 3×3×3 air pocket at 400 K sealed in a 290 K stone box, 200 consecutive engine calls with
 * each call's outputs fed back as the next call's inputs (exactly what the scheduler's store does).</p>
 */
@Tag("integration")
class JniSeamEnergyIT {

    private static final int N = RegionMarshaller.CHUNK_N;
    private static final char L_VOID = 0, L_STONE = 1, L_AIR = 2;
    private static final List<Material> LUT =
            List.of(TestMaterials.voidMat(), TestMaterials.stone(), TestMaterials.air());
    private static final int EPOCH = 4401;
    private static final int PASSES = 0b11;   // conduction | advection — the live unified step
    private static final int STEPS = 200;
    private static final double DT = 1.0;
    private static final float STONE_M = 2000f, STONE_CP = 840f, AIR_M = 1.2f, AIR_CP = 1005f;

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    private record Run(double heatToStone, double booksDrift, double absE) {}

    /** Drive STEPS real JNI calls; {@code roundEToFloat} narrows E between calls — the old float32 seam. */
    private static Run run(NativeEngine engine, boolean roundEToFloat) {
        char[] mat = new char[N];
        float[] mass = new float[N], t = new float[N];
        double[] e = new double[N];
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
            int i = RegionMarshaller.colIdx(6 + x, 100 + y, 6 + z);
            boolean shell = x == 0 || x == 4 || y == 0 || y == 4 || z == 0 || z == 4;
            mat[i] = shell ? L_STONE : L_AIR;
            mass[i] = shell ? STONE_M : AIR_M;
            t[i] = shell ? 290f : 400f;
            // Single-slope seeds (neither material has a phase band in this LUT).
            e[i] = (double) mass[i] * (shell ? STONE_CP : AIR_CP) * t[i];
        }
        if (roundEToFloat) for (int i = 0; i < N; i++) e[i] = (float) e[i];
        double stone0 = stoneE(mat, e);
        float[] px = new float[N], py = new float[N], pz = new float[N], p = new float[N], sr = new float[N];
        char[] slotIx = new char[RegionMarshaller.SLOT_N];
        float[] slotMass = new float[RegionMarshaller.SLOT_N];
        double drift = 0.0, absE = 0.0;
        for (int n = 0; n < STEPS; n++) {
            ColumnTask task = new ColumnTask(0, 0, mat, mass, t, px, py, pz, p, sr, e, slotIx, slotMass);
            RegionStepResult res = engine.stepWorld(List.of(task), EPOCH, DT, PASSES, List.of());
            ColumnResult r = res.columns().get(0);
            double[] eOut = r.enthalpy();
            if (roundEToFloat) for (int i = 0; i < N; i++) eOut[i] = (float) eOut[i];
            double dE = 0.0;
            absE = 0.0;
            for (int i = 0; i < N; i++) { dE += eOut[i] - e[i]; absE += Math.abs(eOut[i]); }
            drift += dE + res.boundaryE() - res.dissipationE();
            mat = r.matIx(); mass = r.mass(); t = r.temperature();
            px = r.momX(); py = r.momY(); pz = r.momZ(); p = r.p(); sr = r.swapReady(); e = eOut;
            slotIx = r.slotIx(); slotMass = r.slotMass();
        }
        return new Run(stoneE(mat, e) - stone0, drift, absE);
    }

    private static double stoneE(char[] mat, double[] e) {
        double s = 0.0;
        for (int i = 0; i < N; i++) if (mat[i] == L_STONE) s += e[i];
        return s;
    }

    @Test
    void doubleSeamDeliversEveryJouleTheFloat32SeamDestroyed() {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(EPOCH, LUT);

        Run exact = run(engine, false);
        Run f32 = run(engine, true);
        double lost = (exact.heatToStone() - f32.heatToStone()) / exact.heatToStone();

        assertTrue(exact.heatToStone() > 1e3,
                "NON-VACUOUS: real heat reaches the stone (" + exact.heatToStone() + " J)");
        assertTrue(Math.abs(exact.booksDrift()) <= 1e-9 * exact.absE(),
                "grand books ΣE + boundaryE − dissipation close over " + STEPS + " real JNI calls: drift "
                        + exact.booksDrift() + " J vs Σ|E| " + exact.absE() + " J");
        // Corruption sensitivity: the old float32 seam must measurably destroy heat bound for the stone,
        // and break the books — otherwise the gates above could pass vacuously.
        assertTrue(lost > 1e-3,
                "float32 seam destroys heat bound for the stone (lost fraction " + lost + ")");
        assertTrue(Math.abs(f32.booksDrift()) > 1e-9 * f32.absE(),
                "float32 seam breaks the books (drift " + f32.booksDrift() + " J)");
    }
}
