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
import net.rainbowcreation.orge.scheduler.InjectionDrain;
import net.rainbowcreation.orge.scheduler.PendingInjections;
import net.rainbowcreation.orge.scheduler.StepValidator;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration gate for Task G2 of the durable-material plan (event-driven BREAK → durable
 * {@code orge:vacuum}) on the REAL {@code liborge.so}. Mirrors the harness of
 * {@link PlacementInjectionPipelineIT} (native-load guard, {@link TestMaterials} LUT, engine index
 * {@code x + 16*y + 6144*z}).
 *
 * <p>The break path is the inverse of the placement path: a removal {@link PendingInjections.Intent}
 * ({@code removal()==true}) is run through {@link InjectionDrain#applyToColumn}, which stomps the broken
 * cell to the index-0 vacuum sentinel (matIx 0, mass 0) and emits NO {@link EngineInjection}. The cell
 * therefore enters the engine step already vacuum; a neighbour fluid then flows into it via normal
 * advection. Two legs are proven on the real engine, plus a durability leg at the SectionStore/assembler
 * seam:</p>
 * <ol>
 *   <li><b>break → vacuum + flow-in:</b> a STONE cell adjacent to WATER is broken (removal intent →
 *       drain stomp to vacuum). After {@code engine.stepWorld}, the broken cell holds water (it flowed
 *       in) and the §9 movable ledger is {@code conserved()} for water (the removed solid is immovable,
 *       outside the movable ledger — like F2 — so NO sealedLoss term is needed);</li>
 *   <li><b>durability (the keystone, no air re-seed):</b> a cell whose durable identity is
 *       {@code orge:vacuum} with no neighbour inflow round-trips a snapshot→writeBack cycle as vacuum —
 *       the engine leaves an empty vacuum cell empty (matIx 0, mass 0), so the write-back re-persists
 *       vacuum and NO 1.2 kg {@code orge:air} is ever fabricated.</li>
 * </ol>
 */
@Tag("integration")
class BreakRemovalPipelineIT {

    // LUT slot convention: 0 = void/vacuum, 1 = water, 2 = air, 3 = stone (immovable solid).
    private static final char VOID = 0, WATER = 1, AIR = 2, STONE = 3;
    private static final List<Material> LUT = List.of(
            TestMaterials.voidMat(), TestMaterials.water(), TestMaterials.air(), TestMaterials.stone());

    private static final Identifier ORGE_VACUUM = Identifier.fromNamespaceAndPath("orge", "vacuum");

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
     * Leg 1: a STONE floor cell is broken (removal intent) directly beneath a WATER cell. The drain
     * stomps the stone cell to the index-0 vacuum sentinel (matIx 0, mass 0) and emits no injection;
     * the engine advection step then drops the water DOWN into the vacated cell. Asserts the broken
     * cell enters the step as vacuum, the water flows in, and the §9 movable ledger conserves water.
     */
    @Test
    void brokenCellBecomesVacuumThenWaterFlowsInAndLedgerConservesWater() {
        NativeEngine engine = engineOrSkip();

        final int floor = cellIndex(3, 0, 4);   // the broken stone cell (floor)
        final int above = cellIndex(3, 1, 4);   // the water cell directly above it

        char[] mat   = new char[RegionMarshaller.CHUNK_N];   // all 0 = void
        float[] mass = new float[RegionMarshaller.CHUNK_N];  // all 0
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 290f);

        // Stone floor + water sitting on top of it (the pre-break state).
        mat[floor]  = STONE;  mass[floor]  = 2000f; temp[floor]  = 290f;
        mat[above]  = WATER;  mass[above]  = 1000f; temp[above]  = 290f;

        List<ColumnTask> cols = new ArrayList<>();
        cols.add(new ColumnTask(0, 0, mat, mass, temp));
        ColumnTask task = cols.get(0);

        // ---- Drain the BREAK removal intent (exactly what captureBreak enqueues). ----
        // applyToColumn stomps the broken cell to the index-0 vacuum sentinel and emits NO injection.
        PendingInjections.Intent removal = new PendingInjections.Intent(
                task != null ? net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "overworld") : null,
                0, 0, floor, ORGE_VACUUM, 0f, 0f, true);
        List<EngineInjection> injections = new ArrayList<>();
        List<PendingInjections.Intent> emitted = new ArrayList<>();
        InjectionDrain.applyToColumn(0, task.matIx(), task.mass(),
                id -> (char) LUT.indexOf(LUT.stream().filter(m -> m.id().equals(id)).findFirst().orElse(TestMaterials.voidMat())),
                List.of(removal),
                cell -> null,        // recorded incumbent (unused for a removal)
                cell -> 0f,          // incumbent mass (unused for a removal)
                injections, emitted);

        // The drain emitted NO injection (a removal never places) and stomped the cell to vacuum.
        assertTrue(injections.isEmpty(), "a removal emits NO engine injection");
        assertEquals(1, emitted.size(), "the removal intent is marked emitted (clear-on-success)");
        assertEquals(VOID, task.matIx()[floor], "broken cell enters the step as the index-0 vacuum sentinel");
        assertEquals(0f, task.mass()[floor], 0f, "broken cell enters the step at 0 kg");

        // ---- Drive the REAL native engine: water falls into the vacated cell. ----
        RegionStepResult r = engine.stepWorld(cols, LUT, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION, injections);
        ColumnResult out = r.columns().get(0);

        // Leg 1a: the broken cell received water flowing in from above.
        assertEquals(WATER, out.matIx()[floor], "water flowed DOWN into the broken (vacuum) cell");
        assertTrue(out.mass()[floor] > 0f, "the broken cell now holds water mass");

        // Leg 1b: water is conserved (relocated, never created/deleted). The removed STONE is immovable
        // (viscosity +INF), outside the §9 movable ledger's scope, so NO sealedLoss term is needed —
        // the broken solid left at the assembly boundary (the drain), not inside the step.
        double waterTotal = 0;
        for (int i = 0; i < out.matIx().length; i++) {
            if (out.matIx()[i] == WATER) waterTotal += out.mass()[i];
        }
        assertEquals(1000.0, waterTotal, 1e-2, "water relocated into the vacuum, total conserved");

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(out.mass(), task.mass(), task.matIx(), out.matIx(), LUT);
        assertTrue(ledger.conserved(),
                "the §9 movable ledger conserves water; the immovable broken stone needs no sealedLoss term");
    }

    /**
     * Leg 2 (durability — the keystone, no air re-seed): a cell whose durable identity is
     * {@code orge:vacuum} and that has NO neighbour inflow stays vacuum across a step. The engine leaves
     * an empty vacuum cell empty (matIx 0, mass 0); the write-back re-persists the index-0 sentinel — so
     * the assembler reads vacuum next cycle and NO 1.2 kg {@code orge:air} is fabricated. Proven here at
     * the engine seam (an isolated vacuum cell surrounded by void), which is exactly the state the
     * SectionStore round-trips: stored vacuum → assembled matIx 0 / mass 0 → engine output matIx 0 /
     * mass 0 → write-back persists vacuum (effective-species rule records the input 0 = vacuum, never air).
     */
    @Test
    void durableVacuumCellStaysVacuumWithNoInflowNoAirReseed() {
        NativeEngine engine = engineOrSkip();

        final int broken = cellIndex(3, 0, 4);   // an isolated vacuum cell, no neighbour fluid

        char[] mat   = new char[RegionMarshaller.CHUNK_N];   // all 0 = vacuum (the assembled stored-vacuum state)
        float[] mass = new float[RegionMarshaller.CHUNK_N];  // all 0
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        Arrays.fill(temp, 290f);
        // broken stays matIx 0 / mass 0 — durable vacuum with nothing around it to flow in.

        List<ColumnTask> cols = new ArrayList<>();
        cols.add(new ColumnTask(0, 0, mat, mass, temp));
        ColumnTask task = cols.get(0);

        RegionStepResult r = engine.stepWorld(cols, LUT, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION, List.of());
        ColumnResult out = r.columns().get(0);

        // The vacuum cell is left vacuum: matIx 0, mass 0. No air (matIx 2) and no mass was fabricated.
        assertEquals(VOID, out.matIx()[broken], "an empty durable-vacuum cell stays the index-0 sentinel");
        assertEquals(0f, out.mass()[broken], 1e-6f, "no mass fabricated into the vacuum cell (no 1.2 kg air)");

        // Effective-species rule the write-back applies: outMat[broken]==0 ⇒ falls back to inMat[broken]==0
        // (vacuum) — so the durable identity re-persisted is orge:vacuum, NEVER orge:air. Confirm no AIR
        // appeared anywhere from nothing (the whole region was vacuum in, must be vacuum out).
        for (int i = 0; i < out.matIx().length; i++) {
            char effective = out.matIx()[i] != 0 ? out.matIx()[i] : task.matIx()[i];
            assertEquals(VOID, effective,
                    "no orge:air fabricated anywhere: every cell's effective species stays vacuum");
        }
    }
}
