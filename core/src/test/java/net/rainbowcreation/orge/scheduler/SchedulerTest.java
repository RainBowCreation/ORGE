package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scheduler orchestration over the whole-region COLUMN path (DESIGN 2026-06-01). Drives the same
 * IDLE→AWAITING state machine, deadline/grace, health throttle, and freeze semantics the per-section
 * path had, but through {@code snapshotColumns} / {@code engine.stepWorld} / {@code writeBackColumn}.
 * Region-wide conservation + HOLD are covered by {@link RegionSchedulerTest}.
 */
class SchedulerTest {

    private static final Identifier OVERWORLD = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    private static final class FakeRunner implements StepRunner {
        Callable<List<StepResult>> task;
        boolean done;
        boolean cancelled;
        List<StepResult> canned;
        RuntimeException failure;

        @Override
        public Handle submit(Callable<List<StepResult>> t) {
            this.task = t;
            this.done = false;
            this.cancelled = false;
            this.canned = null;
            this.failure = null;
            return new Handle() {
                @Override public boolean isDone() { return done; }
                @Override public List<StepResult> result() {
                    if (failure != null) throw failure;
                    if (canned != null) return canned;
                    try { return task.call(); } catch (Exception e) { throw new RuntimeException(e); }
                }
                @Override public void cancel() { cancelled = true; }
            };
        }
    }

    private static final class FakeWorld implements ThermalWorld {
        ColumnBatch batch;
        final List<ColumnEntry> writtenEntries = new ArrayList<>();
        final List<ColumnResult> writtenResults = new ArrayList<>();
        int snapshots;
        /** Optional server-thread cost injected into snapshotColumns() to exercise the wall-time throttle. */
        long snapshotSleepMillis;

        @Override public ColumnBatch snapshotColumns(int range) {
            snapshots++;
            if (snapshotSleepMillis > 0) {
                long end = System.nanoTime() + snapshotSleepMillis * 1_000_000L;
                while (System.nanoTime() < end) { /* busy-wait: real server-thread wall-time */ }
            }
            return batch;
        }
        @Override public void writeBackColumn(ColumnEntry entry, ColumnResult result) {
            writtenEntries.add(entry);
            writtenResults.add(result);
        }
    }

    /** A fluid material with non-zero defaultMass so a column carries a tracked species. */
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

    /** One full-height column at (0,0): all water (matIx 1) at the given uniform temperature, 1000 kg/cell. */
    private static ColumnTask column(float temp) {
        int N = RegionMarshaller.CHUNK_N;
        char[] m = new char[N];
        float[] mass = new float[N];
        float[] t = new float[N];
        java.util.Arrays.fill(m, (char) 1);
        java.util.Arrays.fill(mass, 1000f);
        java.util.Arrays.fill(t, temp);
        return new ColumnTask(0, 0, m, mass, t);
    }

    private static ThermalWorld.ColumnBatch oneColumnBatch(float temp) {
        return new ThermalWorld.ColumnBatch(
                List.of(new ThermalWorld.ColumnEntry(OVERWORLD, 0, 0, column(temp))),
                lut());
    }

    private static Worker worker() {
        return new Worker(UUID.randomUUID(), true, 2, 4, Scheduler.COMPUTE_BUDGET_MILLIS, 3);
    }

    /**
     * A fake engine that adds {@code delta} to every temperature cell ONLY on a conduction pass; the
     * advection pass is identity on temperature. Mass + species carried through unchanged. Mirrors the
     * old deltaEngine so the conduction-only assertions survive the advection cadence.
     */
    private static OrgeEngine deltaEngine(float delta, double millis) {
        return new OrgeEngine() {
            @Override public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                    double dt, int passes) {
                boolean conduction = (passes & PASS_CONDUCTION) != 0;
                List<ColumnResult> out = new ArrayList<>(columns.size());
                for (ColumnTask c : columns) {
                    float[] r = c.temperature().clone();
                    if (conduction) for (int i = 0; i < r.length; i++) r[i] += delta;
                    out.add(new ColumnResult(c.matIx().clone(), c.mass().clone(), r));
                }
                return out;
            }
            @Override public double lastStepMillis() { return millis; }
        };
    }

    private static final class RecordingEngine implements OrgeEngine {
        static final class Call { final int passes; final double dt; Call(int p, double d) { passes = p; dt = d; } }
        final List<Call> calls = new ArrayList<>();
        @Override public List<ColumnResult> stepWorld(List<ColumnTask> in, List<Material> lut,
                                                      double dtSeconds, int passes) {
            calls.add(new Call(passes, dtSeconds));
            List<ColumnResult> out = new ArrayList<>(in.size());
            for (ColumnTask t : in)                       // ColumnResult is (matIx, mass, temperature) — no cx/cz
                out.add(new ColumnResult(t.matIx().clone(), t.mass().clone(), t.temperature().clone()));
            return out;
        }
        @Override public double lastStepMillis() { return 0.0; }
    }

    private static void tickCompleting(Scheduler s, FakeRunner runner, int count) {
        for (int i = 0; i < count; i++) {
            runner.done = true;
            s.onServerTick();
        }
    }

    @Test
    void submitsOnTheFirstAdvectionBoundaryEveryFiveTicks() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 4; i++) s.onServerTick();
        assertEquals(0, world.snapshots, "no snapshot before the first advection boundary (tick 5)");
        s.onServerTick();
        assertEquals(1, world.snapshots, "snapshot taken at the 5-tick advection boundary");
        assertNotNull(runner.task, "an advection step was submitted");
    }

    @Test
    void combinedStepRunsBothPassesEveryFiveTicks() {
        RecordingEngine engine = new RecordingEngine();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        FakeRunner runner = new FakeRunner();
        Scheduler s = new Scheduler(engine, world, runner, worker());
        for (int i = 0; i < Scheduler.ADVECTION_TICKS; i++) s.onServerTick(true);
        runner.done = true;
        s.onServerTick(true);                              // service the in-flight job
        assertEquals(1, engine.calls.size(), "exactly one combined stepWorld call");
        int passes = engine.calls.get(0).passes;
        assertTrue((passes & OrgeEngine.PASS_CONDUCTION) != 0, "conduction ran");
        assertTrue((passes & OrgeEngine.PASS_ADVECTION) != 0, "advection ran");
    }

    @Test
    void overrunHoldsWithoutSecondSubmitOrCancel() {
        RecordingEngine engine = new RecordingEngine();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        FakeRunner runner = new FakeRunner();
        Scheduler s = new Scheduler(engine, world, runner, worker());
        for (int i = 0; i < Scheduler.ADVECTION_TICKS; i++) s.onServerTick(true);   // submit #1 at tick 5
        runner.done = false;                                                        // job never completes (overruns)
        // Cross several 5-tick boundaries while still in flight: the AWAITING gate must hold —
        // no new snapshot/submit, and no hard-cancel until the 2*TICKS_PER_STEP grace window.
        for (int i = 0; i < Scheduler.ADVECTION_TICKS * 3; i++) s.onServerTick(true); // ticks 6..20 (<40 grace)
        assertEquals(1, world.snapshots, "no second submit while in flight (exactly one snapshot)");
        assertFalse(runner.cancelled, "no hard-cancel before the grace window");
    }

    @Test
    void combinedStepWritesBackConductedResult() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        // Conduction now fires on EVERY 5-tick boundary (combined step), so every completed step
        // applies +5; FakeWorld re-snapshots 300 each cycle, so the last write-back is 305.
        tickCompleting(s, runner, 21);

        float lastT = world.writtenResults.get(world.writtenResults.size() - 1).temperature()[0];
        assertEquals(305f, lastT, "conduction input 300 + delta 5, validated, written on every boundary");
        assertTrue(w.onTimeStreak() >= 1, "on-time, under-budget steps advanced the streak");
    }

    @Test
    void deadlineMissHoldsPreviousThenCancelsAndDropsRange() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 5; i++) s.onServerTick();
        for (int i = 0; i < Scheduler.TICKS_PER_STEP * 2; i++) s.onServerTick();
        assertTrue(runner.cancelled, "step cancelled after the grace window");
        assertEquals(0, world.writtenResults.size(), "no write-back on a missed deadline (held previous)");
        assertEquals(1, w.range(), "late step dropped the range from 2 to 1");
    }

    @Test
    void completionDuringGraceCountsAsLate() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 5; i++) s.onServerTick();
        for (int i = 0; i < Scheduler.TICKS_PER_STEP; i++) s.onServerTick();
        runner.done = true;
        s.onServerTick();
        assertEquals(1, world.writtenResults.size(), "a late-but-finished step still writes back");
        assertEquals(1, w.range(), "but it counts as late -> range dropped");
    }

    @Test
    void engineFailureHoldsPreviousAndDropsRange() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 5; i++) s.onServerTick();
        runner.failure = new RuntimeException("engine boom");
        runner.done = true;
        s.onServerTick();
        assertEquals(0, world.writtenResults.size(), "failed step writes nothing");
        assertEquals(1, w.range(), "failed step treated as late");
    }

    @Test
    void emptyBatchDoesNotSubmitAndStaysIdle() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = new ThermalWorld.ColumnBatch(List.of(), lut());
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 40; i++) s.onServerTick();
        assertNull(runner.task, "nothing submitted for an empty batch");
        assertTrue(world.snapshots >= 1, "but it still tried to snapshot at a cadence boundary");
    }

    @Test
    void frozenTicksDoNotAdvanceOrSubmit() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 40; i++) s.onServerTick(false);
        assertEquals(0, world.snapshots, "no snapshot while game ticks are frozen");
        assertNull(runner.task, "nothing submitted while frozen");

        for (int i = 0; i < 5; i++) s.onServerTick(true);
        assertEquals(1, world.snapshots, "stepping resumes once ticks advance again");
        assertNotNull(runner.task, "a step is submitted after the freeze lifts");
    }

    @Test
    void longFreezeDoesNotCancelAnInFlightStep() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 5; i++) s.onServerTick(true);
        for (int i = 0; i < Scheduler.TICKS_PER_STEP * 5; i++) s.onServerTick(false);
        assertFalse(runner.cancelled, "a freeze must not burn the in-flight grace window");
        assertEquals(0, world.writtenResults.size(), "no write-back until the frozen step completes");

        runner.done = true;
        s.onServerTick(true);
        assertEquals(1, world.writtenResults.size(), "the step writes back once ticks resume");
        assertEquals(1, w.onTimeStreak(), "and counts as on-time (freeze did not consume the deadline)");
    }

    @Test
    void noArgTickStillAdvances() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 5; i++) s.onServerTick();
        assertEquals(1, world.snapshots, "the no-arg overload advances as before (gameAdvancing=true)");
    }

    @Test
    void throttleIgnoresHugeNativeStepTimeWhenServerThreadIsCheap() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 5_000.0), world, runner, w);

        tickCompleting(s, runner, 6);
        assertEquals(2, w.range(), "huge native step time does NOT drop the range");
        assertTrue(w.onTimeStreak() >= 1, "a cheap server-thread cycle counts as healthy");
    }

    @Test
    void catchUpDtClampsToMaxCatchup() {
        RecordingEngine engine = new RecordingEngine();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        FakeRunner runner = new FakeRunner();
        Scheduler s = new Scheduler(engine, world, runner, worker());
        // On-pace: 5 ticks -> dt == 0.25.
        for (int i = 0; i < Scheduler.ADVECTION_TICKS; i++) s.onServerTick(true);
        runner.done = true; s.onServerTick(true);
        assertEquals(0.25, engine.calls.get(0).dt, 1e-9, "on-pace dt = 0.25");
        // Overrun: job not done for 15 ticks, then completes -> next submit dt clamps to 0.5.
        runner.done = false;
        for (int i = 0; i < 15; i++) s.onServerTick(true); // >10 ticks of accumulation so the un-clamped dt (>0.5) hits the MAX_CATCHUP ceiling
        runner.done = true; s.onServerTick(true);          // completes late
        for (int i = 0; i < Scheduler.ADVECTION_TICKS; i++) s.onServerTick(true);
        runner.done = true; s.onServerTick(true);
        double catchUp = engine.calls.get(engine.calls.size()-1).dt;
        assertEquals(Scheduler.MAX_CATCHUP_SECONDS, catchUp, 1e-9, "catch-up dt clamps to MAX_CATCHUP");
    }

    @Test
    void throttleBacksOffWhenServerThreadSnapshotIsExpensive() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneColumnBatch(300f);
        world.snapshotSleepMillis = (long) Scheduler.COMPUTE_BUDGET_MILLIS + 25;
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 1.0), world, runner, w);

        tickCompleting(s, runner, 6);
        assertEquals(1, w.range(), "an over-budget server-thread snapshot drops the range from 2 to 1");
    }
}
