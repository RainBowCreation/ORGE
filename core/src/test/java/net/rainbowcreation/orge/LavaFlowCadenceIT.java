package net.rainbowcreation.orge;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the "lava never flows" in-game bug. The native {@code orgeStepWorld} rebuilds a
 * fresh {@code World} every call, so {@code World.simClock} (the per-World sim-time clock that drives
 * the viscosity frontier cadence) used to reset to 0 each step — every step ran with t0=0,t1=dt, and
 * the cadence gate {@code floor(t1/period)!=floor(t0/period)} never tripped for high-viscosity lava
 * (period ≈ 6.25 s ≫ dt=0.25 s). The fix threads an accumulating sim clock from the persistent
 * {@link NativeEngine} into the native step so the frontier actually advances over real sim time.
 */
@Tag("integration")
class LavaFlowCadenceIT {

    private static final int N = RegionMarshaller.CHUNK_N;
    // matIx LUT slots: 0=void, 1=air, 2=lava, 3=stone(immovable solid floor — has mass so it is NOT vacuum).
    private static final char VOID = 0, AIR = 1, LAVA = 2, STONE = 3;

    private static boolean nativeAvailable() {
        try { NativeLoader.load(); return true; } catch (Throwable t) { return false; }
    }

    private static Material mat(String id, float hc, float k, float mol, float mn, float mx, float visc) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(hc).thermalConductivity(k).molarMass(mol)
                .minMass(mn).maxMass(mx).viscosity(visc)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    /** Real LUT: lava molar 0.060 / min 400 / max 3100 / visc 100 / T 1400; air molar 0.002 / min 1 / max 1000 / visc ~0. */
    private static List<Material> lut() {
        List<Material> lut = new ArrayList<>();
        lut.add(mat("orge:void",  0f,    0f,     0f,     0f,    0f,    Float.POSITIVE_INFINITY));
        lut.add(mat("orge:air",   1005f, 0.025f, 0.002f, 1f,    1000f, 0.00002f));
        lut.add(mat("orge:lava",  1000f, 1.0f,   0.060f, 400f,  3100f, 100f));
        lut.add(mat("orge:stone", 800f,  2.0f,   0.270f, 2700f, 2700f, Float.POSITIVE_INFINITY)); // immovable solid floor (has mass -> not vacuum)
        return lut;
    }

    private static int idx(int x, int y, int z) { return x + 16 * y + 6144 * z; }
    private static void put(char[] mi, float[] m, float[] t, int x, int y, int z, char ix, float mass, float temp) {
        int i = idx(x, y, z); mi[i] = ix; m[i] = mass; t[i] = temp;
    }

    private static final int FLOOR_Y = 40;
    private static final int FLUID_Y = 41;

    /** Stone floor at y=40 across the chunk; an air slab at y=41; ONE lava cell in its centre. */
    private static ColumnTask seedColumn() {
        char[] matIx = new char[N]; float[] mass = new float[N]; float[] tIn = new float[N];
        for (int i = 0; i < N; i++) { matIx[i] = 0; mass[i] = 0f; tIn[i] = 300f; }
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            put(matIx, mass, tIn, x, FLOOR_Y, z, STONE, 2700f, 300f);            // immovable solid floor
            put(matIx, mass, tIn, x, FLUID_Y, z, AIR, 1.2f, 300f);               // finite air slab
        }
        put(matIx, mass, tIn, 8, FLUID_Y, 8, LAVA, 3100f, 1400f);                // one lava cell on the floor surface
        return new ColumnTask(0, 0, matIx, mass, tIn);
    }

    /** Count cells on the fluid plane occupied (mostly) by lava. */
    private static int lavaCells(ColumnResult r) {
        int n = 0;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            if (r.matIx()[idx(x, FLUID_Y, z)] == LAVA) n++;
        }
        return n;
    }

    private static float massOf(ColumnResult r, char species) {
        float m = 0f;
        for (int i = 0; i < N; i++) if (r.matIx()[i] == species) m += r.mass()[i];
        return m;
    }

    @Test
    void lavaFrontierAdvancesOverItsViscosityPeriod() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        OrgeEngine engine = new NativeEngine();
        engine.registerMaterials(5, lut());

        List<ColumnTask> cols = List.of(seedColumn());
        ColumnResult first = engine.stepWorld(cols, 5, 0.25,
                OrgeEngine.PASS_ADVECTION).get(0);
        int startLava = lavaCells(first);
        float startLavaMass = massOf(first, LAVA);

        ColumnResult r = first;
        for (int s = 0; s < 40; s++) { // ~10 s sim time > lava's ~6.25 s period
            r = engine.stepWorld(
                    List.of(new ColumnTask(0, 0, r.matIx(), r.mass(), r.temperature())),
                    5, 0.25, OrgeEngine.PASS_ADVECTION).get(0);
        }
        int endLava = lavaCells(r);

        assertTrue(endLava > startLava,
                "lava frontier must advance: start=" + startLava + " end=" + endLava + " cells");

        // Per-species mass conserved across the run (no creation/destruction of lava).
        assertEquals(startLavaMass, massOf(r, LAVA), 1.0f, "lava mass conserved");
    }
}
