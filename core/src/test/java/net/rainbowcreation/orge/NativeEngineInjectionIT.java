package net.rainbowcreation.orge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.EngineInjection;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.RegionStepResult;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the injection-aware
 * {@link NativeEngine#stepWorld(List, List, double, int, List)} override (Task 2 of the
 * placement-injection plan). Verifies that the native injection channel is actually wired:
 * a water injection displaces incumbent air, air is conserved (relocated not deleted),
 * and the ledger reports the placed mass.
 *
 * <p>Mirrors the harness of the sibling live-pipeline tests (same native-load guard, same
 * {@link TestMaterials} factories, same engine index formula {@code x + 16*y + 6144*z}).</p>
 */
@Tag("integration")
class NativeEngineInjectionIT {

    // LUT slot convention: 0 = void, 1 = water, 2 = air.
    // water molar (0.018) > air molar (0.002) so water sinks, air rises under molar-sort.
    private static final char VOID = 0, WATER = 1, AIR = 2;
    private static final List<Material> LUT =
            List.of(TestMaterials.voidMat(), TestMaterials.water(), TestMaterials.air());

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    /** Engine cell index formula: x + 16*y + 6144*z */
    private static int cellIndex(int x, int y, int z) { return x + 16 * y + 6144 * z; }

    /**
     * Inject 1000 kg water into the floor cell (y=0) that holds 1.2 kg air.
     *
     * <p>Scenario design: the injection target is at (x=3, y=0, z=4) -- the very bottom of the
     * full-height column. After injection, water sits on the floor and CANNOT fall further (y-1
     * is out of bounds). The displaced air has the UP (y=1) escape path since DOWN fails and all
     * four horizontal neighbours are absent (only one column is loaded). The rest of the column
     * is void before injection, so the engine step does not move the water away.</p>
     *
     * <p>Assertions (all must hold):</p>
     * <ul>
     *   <li>Target cell (y=0) is water at ~1000 kg after the step</li>
     *   <li>Air mass conserved across the column (relocated to y=1, not deleted)</li>
     *   <li>{@code injected[WATER] approx 1000} (ledger booked the placement)</li>
     *   <li>{@code sealedLoss[AIR] approx 0} (air escaped UP to the void cell above)</li>
     *   <li>{@code injected.length == lut.size()}</li>
     * </ul>
     */
    @Test
    void injectWaterDisplacesAirAndReportsLedger() {
        NativeEngine engine = engineOrSkip();

        // ci: inject at (x=3, y=0, z=4) -- floor of the column; nothing below; UP is void.
        final int ci = cellIndex(3, 0, 4);

        char[] mat  = new char[RegionMarshaller.CHUNK_N];   // all 0 = void
        float[] mass = new float[RegionMarshaller.CHUNK_N]; // all 0
        float[] temp = new float[RegionMarshaller.CHUNK_N]; // all 0 -- filled below

        // Fill all temperatures with ambient 290 K to avoid 0 K artefacts.
        Arrays.fill(temp, 290f);

        // Place air at the injection target cell (y=0).
        mat[ci]  = AIR;
        mass[ci] = 1.2f;
        temp[ci] = 300f;

        // y=1 cell (above) stays void -- the UP escape path for the displaced air.

        List<ColumnTask> cols = new ArrayList<>();
        cols.add(new ColumnTask(0, 0, mat, mass, temp));

        // Use passes=0 (no engine step) so nothing moves after the injection. Issue #7 made
        // conduction RIDE RESOLVE (one unified step; either pass bit now runs full physics, and
        // a leveling step would spread the placed 1000 kg), so "inject + read back, no motion"
        // is expressed as zero pass bits — injections are applied before the pass dispatch.
        engine.registerMaterials(1, LUT);
        RegionStepResult r = engine.stepWorld(cols, 1, 0.25,
                0,
                List.of(new EngineInjection(0, ci, WATER, 1000f, 290f)));

        ColumnResult out = r.columns().get(0);

        // 1) Target cell (y=0) became water and stayed there (floor, nowhere to fall).
        assertEquals(WATER, out.matIx()[ci], "target cell became water");
        assertEquals(1000f, out.mass()[ci], 1e-2f, "water placed at 1000 kg");

        // 2) Air conserved: 1.2 kg was at ci; must be relocated (not deleted).
        //    Sum all air cells across the output column.
        double airTotal = 0;
        for (int i = 0; i < out.matIx().length; i++) {
            if (out.matIx()[i] == AIR) airTotal += out.mass()[i];
        }
        assertEquals(1.2, airTotal, 1e-2, "air relocated, not deleted");

        // 3) Ledger array length matches LUT size.
        assertEquals(LUT.size(), r.injected().length, "injected.length == lut.size()");

        // 4) Ledger reports the injected water mass.
        assertEquals(1000f, r.injected()[WATER], 1e-2f, "ledger booked the injected water");

        // 5) No sealed loss for air (it escaped UP to void at y=1).
        assertEquals(0f, r.sealedLoss()[AIR], 1e-2f, "no sealed loss (air escaped UP to void)");

        // 6) T10.8: the ENERGY side of the §9 ledger now crosses the JNI (ledgerOut[2n..4n)).
        //    The E-side arrays are present and LUT-length (proves the [4*matCount] ledger was passed
        //    AND the C++ forward-compat guard wrote the E side).
        assertEquals(LUT.size(), r.injectedE().length, "injectedE.length == lut.size()");
        assertEquals(LUT.size(), r.sealedE().length, "sealedE.length == lut.size()");

        //    The placed 1000 kg of 290 K water carries real enthalpy: injectedE[WATER] is strongly
        //    positive (E = mass·cp·T booked as a SOURCE; law #9). This proves the E channel is wired,
        //    not silently dropped. (Asserting > a large floor rather than an exact value keeps the test
        //    decoupled from the §8.1 enthalpy curve / latent plateaus.)
        assertTrue(r.injectedE()[WATER] > 1.0e6f,
                "ledger booked the injected water ENERGY (E side surfaced): " + r.injectedE()[WATER]);

        //    Air escaped UP to void (no incumbent destroyed), so no sealed ENERGY either — the mass and
        //    energy sides of the seal agree (both ~0 for air).
        assertEquals(0f, r.sealedE()[AIR], 1e-2f, "no sealed ENERGY (air escaped UP to void)");

        //    Grand-energy closure across the placement seam: with no sealing and one placed species,
        //    the only ledgered energy is the injected water source — sealedE total must be ~0 and the
        //    injected-E source must equal the water entry (nothing leaked into other species).
        double sealedETotal = 0, injectedEOther = 0;
        for (int s = 0; s < LUT.size(); s++) {
            sealedETotal += r.sealedE()[s];
            if (s != WATER) injectedEOther += r.injectedE()[s];
        }
        assertEquals(0.0, sealedETotal, 1e-2, "no sealed energy anywhere (clean displacement)");
        assertEquals(0.0, injectedEOther, 1e-2, "all injected energy is the placed water species");
    }
}
