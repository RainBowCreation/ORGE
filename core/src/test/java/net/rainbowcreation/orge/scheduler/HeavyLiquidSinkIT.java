package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.RegionStepResult;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.rainbowcreation.orge.scheduler.SkyTestWorld.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #88: lava placed on water sinks (Rayleigh–Taylor) instead of spreading into 338 kg films on the
 * surface. The in-game repro through the bundled liborge and the shipped material JSON: a still 7×7×3 water
 * pool in a stone basin, air above, one 1473 K lava cell placed one row above the surface, dt 0.25 s. Before
 * the fix every lava cell was still on the surface row after 30 s; now the rock (lava + its quench target
 * basalt) reaches the pool floor, with every species' mass and the grand E balanced.
 */
@Tag("integration")
class HeavyLiquidSinkIT {

    private static final Identifier LAVA = orge("lava");
    private static final Identifier BASALT = orge("basalt");
    private static final Identifier STEAM = orge("steam");
    private static final Identifier ICE = orge("ice");
    private static final double DT = 0.25;
    private static final int N = RegionMarshaller.CHUNK_N;
    private static final int FY = 100, WT = FY + 3, AIR_TOP = WT + 4;   // basin floor row, water top row
    private static final int X0 = 4, X1 = 10, Z0 = 4, Z1 = 10, XC = 7, ZC = 7;

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    private static double massOf(char[] matIx, float[] mass, char[] slotIx, float[] slotMass, char ix) {
        double s = 0;
        for (int i = 0; i < N; i++) if (matIx[i] == ix) s += mass[i];
        for (int j = 0; j < slotIx.length; j++) if (slotIx[j] == ix) s += slotMass[j];
        return s;
    }

    /** Rock (lava + basalt) inside the pool interior on engine row y — the basin's own stone is outside it. */
    private static double rockOnRow(char[] matIx, float[] mass, char lava, char basalt, int y) {
        double s = 0;
        for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) {
            int i = RegionMarshaller.colIdx(x, y, z);
            if (matIx[i] == lava || matIx[i] == basalt) s += mass[i];
        }
        return s;
    }

    @Test
    void lavaPlacedOnAWaterPoolSinksToTheFloor() {
        NativeEngine engine = engineOrSkip();
        List<Material> ordered = MaterialTable.ordered(registry());
        MaterialLut lut = new MaterialLut(ordered, MaterialTable.slots(ordered));
        char air = lut.indexOf(AIR), stone = lut.indexOf(STONE), water = lut.indexOf(WATER);
        char lava = lut.indexOf(LAVA), basalt = lut.indexOf(BASALT), steam = lut.indexOf(STEAM), ice = lut.indexOf(ICE);
        engine.registerMaterials(1, ordered);

        char[] mat = new char[N];
        float[] mass = new float[N], t = new float[N];
        double[] e = new double[N];
        java.util.Arrays.fill(t, 290f);
        for (int x = X0 - 1; x <= X1 + 1; x++) for (int z = Z0 - 1; z <= Z1 + 1; z++) for (int y = FY; y <= AIR_TOP; y++) {
            boolean wall = y == FY || x < X0 || x > X1 || z < Z0 || z > Z1;
            Identifier id = wall ? STONE : y <= WT ? WATER : AIR;
            int i = RegionMarshaller.colIdx(x, y, z);
            mat[i] = lut.indexOf(id);
            mass[i] = mat(id).defaultMass();
        }
        int placed = RegionMarshaller.colIdx(XC, WT + 1, ZC);   // the placed lava, one row above the surface
        mat[placed] = lava;
        mass[placed] = mat(LAVA).defaultMass();
        t[placed] = 1473f;
        for (int i = 0; i < N; i++)
            if (mass[i] > 0f) e[i] = EnthalpyCurve.cellE(mass[i], ordered.get(mat[i]), lookup(), t[i]);

        float[] px = new float[N], py = new float[N], pz = new float[N], p = new float[N], sr = new float[N];
        char[] slotIx = new char[RegionMarshaller.SLOT_N];
        float[] slotMass = new float[RegionMarshaller.SLOT_N];
        double rock0 = massOf(mat, mass, slotIx, slotMass, lava) + massOf(mat, mass, slotIx, slotMass, basalt);
        double h2o0 = massOf(mat, mass, slotIx, slotMass, water) + massOf(mat, mass, slotIx, slotMass, steam)
                + massOf(mat, mass, slotIx, slotMass, ice);
        double air0 = massOf(mat, mass, slotIx, slotMass, air);
        double stone0 = massOf(mat, mass, slotIx, slotMass, stone);
        double absE = 0, drift = 0;
        int reachedAt = -1;
        for (int n = 0; n < 120; n++) {                       // 30 s
            ColumnTask task = new ColumnTask(0, 0, mat, mass, t, px, py, pz, p, sr, e, slotIx, slotMass);
            RegionStepResult res = engine.stepWorld(List.of(task), 1, DT,
                    OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION, List.of());
            ColumnResult r = res.columns().get(0);
            double dE = 0;
            absE = 0;
            for (int i = 0; i < N; i++) { dE += r.enthalpy()[i] - e[i]; absE += Math.abs(r.enthalpy()[i]); }
            drift += dE + res.boundaryE() - res.dissipationE();
            mat = r.matIx(); mass = r.mass(); t = r.temperature(); e = r.enthalpy();
            px = r.momX(); py = r.momY(); pz = r.momZ(); p = r.p(); sr = r.swapReady();
            slotIx = r.slotIx(); slotMass = r.slotMass();
            if (reachedAt < 0 && rockOnRow(mat, mass, lava, basalt, FY + 1) > 1.0) reachedAt = n + 1;
        }

        double rock1 = massOf(mat, mass, slotIx, slotMass, lava) + massOf(mat, mass, slotIx, slotMass, basalt);
        double h2o1 = massOf(mat, mass, slotIx, slotMass, water) + massOf(mat, mass, slotIx, slotMass, steam)
                + massOf(mat, mass, slotIx, slotMass, ice);
        assertTrue(reachedAt > 0, "the lava reaches the pool floor within 30 s (it floated on the surface before #88)");
        assertEquals(0.0, rockOnRow(mat, mass, lava, basalt, WT + 1), 1.0, "no lava is left floating on the surface row");
        assertEquals(rock0, rock1, 1e-6 * rock0, "rock (lava + basalt) mass exact");
        assertEquals(h2o0, h2o1, 1e-6 * h2o0, "H2O (water + steam + ice) mass exact");
        assertEquals(air0, massOf(mat, mass, slotIx, slotMass, air), 1e-6 * air0 + 1e-3, "air mass exact");
        assertEquals(stone0, massOf(mat, mass, slotIx, slotMass, stone), 1e-6 * stone0, "basin stone mass exact");
        assertEquals(0.0, drift, 1e-6 * absE, "Σ E change = boundary + dissipation books (grand E exact)");
    }
}
