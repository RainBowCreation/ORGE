package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole-region column pipeline through the {@link Scheduler} (DESIGN 2026-06-01 Task 5): one
 * {@code stepWorld} call over ALL columns per cycle, a region-wide per-species §9 ledger, and per-column
 * {@code writeBackColumn} on conservation / atomic HOLD on a deliberately fabricating engine.
 */
class RegionSchedulerTest {

    private static final Identifier OVERWORLD = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    private static final Material WATER = Material.builder(
                    Identifier.fromNamespaceAndPath("minecraft", "water"))
            .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(18f)
            .defaultMass(1000f).defaultTemperature(Float.NaN)
            .viscosity(1f) // finite ⇒ movable (old fluid=true)
            .minTemp(273.15f).maxTemp(373.15f)
            .representativeBlock(Identifier.fromNamespaceAndPath("minecraft", "water"))
            .build();

    private static List<Material> lut() {
        return List.of(MaterialLut.VOID, WATER);
    }

    /** Eager synchronous runner: executes the step at submit and reports done immediately. */
    private static final class EagerRunner implements StepRunner {
        @Override public Handle submit(Callable<List<StepResult>> t) {
            final List<StepResult> out;
            try { out = t.call(); } catch (Exception e) { throw new RuntimeException(e); }
            return new Handle() {
                @Override public boolean isDone() { return true; }
                @Override public List<StepResult> result() { return out; }
                @Override public void cancel() {}
            };
        }
    }

    private static final class FakeWorld implements ThermalWorld {
        ColumnBatch batch;
        final List<ThermalWorld.ColumnEntry> written = new ArrayList<>();
        @Override public ColumnBatch snapshotColumns(int range) { return batch; }
        @Override public void writeBackColumn(ColumnEntry entry, ColumnResult result) {
            written.add(entry);
        }
    }

    /** Records the columns handed to stepWorld; moves {@code transfer} kg from column 0 cell A to
     *  column 1 cell B (a conservative X-seam transfer of water) when {@code conserve} is true, else
     *  fabricates {@code transfer} kg in column 1 with no donor (non-conserving). */
    private static final class SeamEngine implements OrgeEngine {
        int stepWorldCalls;
        int lastColumnCount;
        final boolean conserve;
        final int cellA;
        final int cellB;
        final float transfer;
        SeamEngine(boolean conserve, int cellA, int cellB, float transfer) {
            this.conserve = conserve; this.cellA = cellA; this.cellB = cellB; this.transfer = transfer;
        }
        @Override public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
            throw new UnsupportedOperationException("column path only");
        }
        @Override public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                double dt, int passes) {
            stepWorldCalls++;
            lastColumnCount = columns.size();
            List<ColumnResult> out = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                ColumnTask c = columns.get(i);
                float[] mass = c.mass().clone();
                if (i == 0 && conserve) mass[cellA] -= transfer;   // donor (column 0)
                if (i == 1) mass[cellB] += transfer;               // recipient (column 1)
                out.add(new ColumnResult(c.matIx().clone(), mass, c.temperature().clone()));
            }
            return out;
        }
        @Override public double lastStepMillis() { return 1.0; }
    }

    /** A full-height column: water (matIx 1) in two cells (A=1000kg, B=0), air-ish elsewhere (void/0). */
    private static ColumnTask waterColumn(int cx, int cz, int cellA, float massA) {
        int N = RegionMarshaller.CHUNK_N;
        char[] m = new char[N];
        float[] mass = new float[N];
        float[] t = new float[N];
        java.util.Arrays.fill(t, 290f);
        m[cellA] = 1; mass[cellA] = massA;     // water cell
        return new ColumnTask(cx, cz, m, mass, t);
    }

    private static Worker worker() {
        return new Worker(UUID.randomUUID(), true, 2, 4, 1000.0, 5);
    }

    private static int idx(int x, int y, int z) { return x + 16 * y + 6144 * z; }

    private ThermalWorld.ColumnBatch twoColumnBatch(int cellA) {
        // Column 0 has 1000 kg water at cellA; column 1 (the apron) starts with a 0-kg water cell at cellB.
        ColumnTask a = waterColumn(0, 0, cellA, 1000f);
        int cellB = idx(0, 70 + 64, 0);
        ColumnTask b = waterColumn(1, 0, cellB, 0f);
        return new ThermalWorld.ColumnBatch(
                List.of(new ThermalWorld.ColumnEntry(OVERWORLD, 0, 0, a),
                        new ThermalWorld.ColumnEntry(OVERWORLD, 1, 0, b)),
                lut());
    }

    @Test
    void advectionStepsAllColumnsOnceAndWritesBackEachOnConservation() {
        int cellA = idx(15, 70 + 64, 0);
        int cellB = idx(0, 70 + 64, 0);
        FakeWorld world = new FakeWorld();
        world.batch = twoColumnBatch(cellA);
        SeamEngine engine = new SeamEngine(true, cellA, cellB, 100f);
        Scheduler s = new Scheduler(engine, world, new EagerRunner(), worker());

        // tick 5: advection boundary submits (eager run); tick 6 services + writes back.
        for (int i = 0; i < 6; i++) s.onServerTick();

        assertEquals(1, engine.stepWorldCalls, "exactly one whole-region step per advection cycle");
        assertEquals(2, engine.lastColumnCount, "both columns handed to stepWorld in one call");
        assertEquals(2, world.written.size(), "conserved region -> writeBackColumn per entry");
        assertEquals(0, world.written.get(0).cx());
        assertEquals(1, world.written.get(1).cx());
    }

    @Test
    void nonConservingRegionHoldsTheWholeRegion() {
        int cellA = idx(15, 70 + 64, 0);
        int cellB = idx(0, 70 + 64, 0);
        FakeWorld world = new FakeWorld();
        world.batch = twoColumnBatch(cellA);
        // conserve=false: column 1 GAINS a large mass with no donor -> water's region sum is long beyond
        // the ε·totalCells tolerance (≈1966 kg over 2×98304 cells), so the region must HOLD.
        SeamEngine engine = new SeamEngine(false, cellA, cellB, 5000f);
        Scheduler s = new Scheduler(engine, world, new EagerRunner(), worker());

        for (int i = 0; i < 6; i++) s.onServerTick();

        assertEquals(1, engine.stepWorldCalls, "the region is still stepped once");
        assertEquals(0, world.written.size(), "non-conserving region -> atomic HOLD, no column written back");
    }
}
