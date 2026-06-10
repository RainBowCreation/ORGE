package net.rainbowcreation.orge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import net.rainbowcreation.orge.scheduler.StepValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration gate for Task 9 of the placement-injection plan (Plan 2, Java pipeline).
 *
 * <p>This is the closing E2E proof of the whole fix: a player-placed fluid that previously
 * "appeared then vanished" (a stale write-back stomping the placement) now goes through the engine
 * injection channel, DISPLACES the incumbent air (air relocated, not deleted), PERSISTS at its cell
 * (no vanish), and — critically — is ACCEPTED by the §9 region {@link StepValidator.SpeciesMassLedger}
 * rather than HELD: the ledger reports {@code conserved() == true} once the engine's declared
 * placement deltas ({@code injected}/{@code sealedLoss}) are fed via {@link
 * StepValidator.SpeciesMassLedger#expect}.</p>
 *
 * <p><b>Setup choice (Open Implementer Decision #2): FOCUSED path.</b> There is no live Minecraft
 * server in this harness, so the full {@code MinecraftThermalWorld}+{@code Scheduler} stand-up is
 * impractical (the sibling live ITs that stand up a "world" use a bespoke fake column store, not the
 * real server world). The focused path exercises the load-bearing seam — the engine↔ledger contract
 * on the REAL {@code liborge.so} — which is the heart of this fix. The "intent cleared after success"
 * leg is covered by Task 8's scheduler wiring + Task 3's durable-queue unit tests; here the four
 * NON-NEGOTIABLE legs are proven on the real engine:</p>
 * <ol>
 *   <li>water placed + PERSISTS at the cell (no vanish);</li>
 *   <li>air CONSERVED (relocated to the void cell above, not deleted);</li>
 *   <li>the §9 ledger is NOT HELD ({@code conserved() == true}) with the engine's declared deltas;</li>
 *   <li>(control) WITHOUT declaring the deltas the same ledger HOLDs — proving leg 3 is the
 *       injection-aware acceptance, not a vacuous always-true.</li>
 * </ol>
 *
 * <p>Mirrors the harness of {@link NativeEngineInjectionIT} (same native-load guard, same {@link
 * TestMaterials} LUT, same engine index formula {@code x + 16*y + 6144*z}, same floor-cell geometry
 * so a single {@code PASS_CONDUCTION} step keeps the placed water at the asserted cell).</p>
 */
@Tag("integration")
class PlacementInjectionPipelineIT {

    // LUT slot convention: 0 = void, 1 = water, 2 = air.
    // water molar (0.018) > air molar (0.002) so water sinks, air rises under molar-sort.
    private static final char VOID = 0, WATER = 1, AIR = 2;
    private static final List<Material> LUT =
            List.of(TestMaterials.voidMat(), TestMaterials.water(), TestMaterials.air());

    // Solid-placement LUT: 0 = void, 1 = water, 2 = air, 3 = stone (an immovable solid, viscosity +∞,
    // defaultMass 2000). Proves a SOLID placement injection displaces+seeds on the real engine (bug 3),
    // with no movable() special-casing and no new ledger code (F2 verification).
    private static final char S_WATER = 1, STONE = 3;
    private static final List<Material> SOLID_LUT = List.of(
            TestMaterials.voidMat(), TestMaterials.water(), TestMaterials.air(), TestMaterials.stone());

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
     * E2E: a placement injection of water into a floor cell holding air. Drive the REAL native step
     * with the 5-arg injection overload, then feed the real §9 ledger and assert it ACCEPTS the
     * placement (not held), the water persists, and air is conserved.
     */
    @Test
    void placedWaterDisplacesAirAndPersistsAndLedgerNotHeld() {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(1, LUT);

        // Inject at (x=3, y=0, z=4) -- the floor of the full-height column. Water cannot fall (y-1
        // out of bounds) and all four horizontal neighbours are absent (one column loaded), so the
        // displaced air's only escape is UP to the void cell at y=1. A single PASS_CONDUCTION step
        // keeps the placed water at this exact cell (conduction moves heat, never mass/position).
        final int ci = cellIndex(3, 0, 4);

        char[] mat   = new char[RegionMarshaller.CHUNK_N];   // all 0 = void
        float[] mass = new float[RegionMarshaller.CHUNK_N];  // all 0
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 290f);                              // ambient, avoid 0 K artefacts

        // Incumbent air at the placement target (the cell the player places water into).
        mat[ci]  = AIR;
        mass[ci] = 1.2f;
        temp[ci] = 300f;
        // y=1 above stays void -- the relocation receiver for the displaced air.

        List<ColumnTask> cols = new ArrayList<>();
        cols.add(new ColumnTask(0, 0, mat, mass, temp));

        ColumnTask task = cols.get(0);

        // The placement injection: water, 1000 kg, at the target cell, in column 0.
        EngineInjection inj = new EngineInjection(0, ci, WATER, 1000f, 290f);

        // Drive the REAL native engine through the injection-aware 5-arg overload.
        RegionStepResult r = engine.stepWorld(cols, 1, 0.25,
                0 /* no step: issue #7 unified passes; inject+readback only */, List.of(inj));

        ColumnResult out = r.columns().get(0);

        // ---- Leg 1: water placed and PERSISTS at the cell (no vanish). ----
        assertEquals(WATER, out.matIx()[ci], "placed water persists at the cell (no vanish)");
        assertEquals(1000f, out.mass()[ci], 1e-2f, "placed water persists at ~1000 kg");

        // ---- Leg 2: air CONSERVED across the column (relocated, not deleted). ----
        double airTotal = 0;
        for (int i = 0; i < out.matIx().length; i++) {
            if (out.matIx()[i] == AIR) airTotal += out.mass()[i];
        }
        assertEquals(1.2, airTotal, 1e-2, "air relocated, not deleted");

        // Sanity on the engine's declared placement ledger (the deltas the §9 gate consumes).
        assertEquals(LUT.size(), r.injected().length, "injected.length == lut.size()");
        assertEquals(1000f, r.injected()[WATER], 1e-2f, "engine booked the injected water");
        assertEquals(0f, r.sealedLoss()[AIR], 1e-2f, "no sealed loss (air escaped UP to void)");

        // ---- Leg 3: the §9 region ledger is NOT HELD -- it ACCEPTS the placement. ----
        // Accumulate the per-column dual-index sums, then declare the engine's placement deltas.
        // Air balances on its own index (1.2 in == 1.2 out, relocated); water's +1000 after-before
        // is reconciled by the declared injected[WATER]=1000 delta. conserved() must be TRUE.
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(out.mass(), task.mass(), task.matIx(), out.matIx(), LUT);
        ledger.expect(r.injected(), r.sealedLoss());
        assertTrue(ledger.conserved(),
                "region step ACCEPTS the declared injection -- the §9 ledger is NOT held");

        // ---- Leg 4 (control): without declaring the deltas the SAME data HOLDs. ----
        // Proves leg 3 passes BECAUSE of injection-aware acceptance, not because the step is
        // trivially conservative -- the fabricated +1000 kg water would otherwise trip the HOLD.
        StepValidator.SpeciesMassLedger naive = new StepValidator.SpeciesMassLedger();
        naive.add(out.mass(), task.mass(), task.matIx(), out.matIx(), LUT);
        assertFalse(naive.conserved(),
                "without the declared injection delta the ledger HOLDs -- acceptance is injection-aware");
    }

    /**
     * F2 bug-2/bug-3 core, on the REAL engine: a SOLID placement injection (stone, its defaultMass) into
     * a floor cell holding a WATER incumbent. This is the exact intent {@link
     * net.rainbowcreation.orge.scheduler.PlacementCapture#capture} now enqueues for a solid placement
     * (F1 dropped the movable() gate, so any placed species captures + displaces). It proves:
     * <ul>
     *   <li>bug 3 — the solid SEEDS its full {@code defaultMass} (2000 kg stone) at the cell;</li>
     *   <li>bug 2 — the WATER incumbent is DISPLACED (pushed UP to the void cell above), not deleted;</li>
     *   <li>the engine's mass ledger is {@code conserved()} once the declared injection deltas are fed —
     *       no new ledger code is needed; the species-agnostic injection already handles solids.</li>
     * </ul>
     * Mirrors the harness/geometry of {@link #placedWaterDisplacesAirAndPersistsAndLedgerNotHeld()}.
     */
    @Test
    void placedSolidDisplacesWaterAndSeedsDefaultMassAndLedgerNotHeld() {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(1, SOLID_LUT);

        // Place stone at (x=3, y=0, z=4) -- the floor of the loaded column. The displaced water cannot
        // fall (y-1 out of bounds) and all four horizontal neighbours are absent (one column loaded), so
        // its only escape is UP to the void cell at y=1. A single PASS_CONDUCTION step keeps the placed
        // stone at this exact cell (conduction moves heat, never mass/position).
        final int ci = cellIndex(3, 0, 4);

        char[] mat   = new char[RegionMarshaller.CHUNK_N];   // all 0 = void
        float[] mass = new float[RegionMarshaller.CHUNK_N];  // all 0
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 290f);                              // ambient, avoid 0 K artefacts

        // Incumbent WATER at the placement target (the cell the player places stone into).
        mat[ci]  = S_WATER;
        mass[ci] = 1000f;
        temp[ci] = 290f;
        // y=1 above stays void -- the relocation receiver for the displaced water.

        List<ColumnTask> cols = new ArrayList<>();
        cols.add(new ColumnTask(0, 0, mat, mass, temp));

        ColumnTask task = cols.get(0);

        // The SOLID placement injection: stone, its defaultMass (2000 kg), at the target cell, column 0.
        // This is exactly what PlacementCapture.capture enqueues for a solid placement under F1.
        EngineInjection inj = new EngineInjection(0, ci, STONE, 2000f, 290f);

        // Drive the REAL native engine through the injection-aware 5-arg overload (same call as fluids).
        RegionStepResult r = engine.stepWorld(cols, 1, 0.25,
                0 /* no step: issue #7 unified passes; inject+readback only */, List.of(inj));

        ColumnResult out = r.columns().get(0);

        // ---- bug 3: the solid SEEDS its full defaultMass at the cell (no half-mass, no vanish). ----
        assertEquals(STONE, out.matIx()[ci], "placed stone persists at the cell (no vanish)");
        assertEquals(2000f, out.mass()[ci], 1e-2f, "placed solid seeds its full defaultMass (2000 kg)");

        // ---- bug 2: the WATER incumbent is DISPLACED (relocated UP), not deleted. ----
        double waterTotal = 0;
        int waterCell = -1;
        for (int i = 0; i < out.matIx().length; i++) {
            if (out.matIx()[i] == S_WATER) { waterTotal += out.mass()[i]; waterCell = i; }
        }
        assertEquals(1000.0, waterTotal, 1e-2, "displaced water relocated, not deleted");
        assertFalse(waterCell == ci, "the water was pushed off the placement cell (displaced, not stomped)");

        // Sanity on the engine's declared placement ledger (the deltas the §9 gate consumes).
        assertEquals(SOLID_LUT.size(), r.injected().length, "injected.length == lut.size()");
        assertEquals(2000f, r.injected()[STONE], 1e-2f, "engine booked the injected stone");
        assertEquals(0f, r.sealedLoss()[S_WATER], 1e-2f, "no sealed loss (water escaped UP to void)");

        // ---- the §9 movable-mass ledger CONSERVES the displaced WATER (the movable incumbent). ----
        // The §9 SpeciesMassLedger tracks only MOVABLE species (Material#movable() — finite viscosity);
        // an immovable solid (stone, viscosity +INF) is outside its advection-conservation scope, so the
        // engine's injected[STONE]=2000 delta is NOT consumed by this ledger (a movable-only invariant).
        // What the ledger DOES guarantee here is that the displaced water — the only movable species in
        // play — is conserved on its OWN index: it was relocated (engine injected[WATER]=0,
        // sealedLoss[WATER]=0), never deleted. With no movable mass created or sealed, the ledger holds
        // WITHOUT needing any new ledger code -- the species-agnostic injection already handles solids.
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(out.mass(), task.mass(), task.matIx(), out.matIx(), SOLID_LUT);
        assertTrue(ledger.conserved(),
                "the §9 movable ledger conserves the displaced water (immovable stone is out of its scope)");
    }
}
