package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

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

    private static final class RecordingPhaseChanger
            implements net.rainbowcreation.orge.phase.PhaseChanger {
        final List<SubchunkKey> applied = new ArrayList<>();
        @Override public void applyPhaseChanges(ThermalWorld.BatchEntry entry) {
            applied.add(entry.key());
        }
    }

    private static final class FakeWorld implements ThermalWorld {
        Batch batch;
        final List<float[]> writes = new ArrayList<>();
        final List<float[]> massWrites = new ArrayList<>();
        final List<SubchunkKey> writeKeys = new ArrayList<>();
        int snapshots;

        @Override public Batch snapshot(int range) { snapshots++; return batch; }
        @Override public void writeBack(BatchEntry entry, StepResult r) {
            writeKeys.add(entry.key());
            writes.add(r.temperature());
            massWrites.add(r.mass());
        }
    }

    private static StepTask task(float fill) {
        float[] t = new float[net.rainbowcreation.orge.section.SectionData.CELLS];
        java.util.Arrays.fill(t, fill);
        char[] m = new char[net.rainbowcreation.orge.section.SectionData.CELLS];
        float[] mass = new float[net.rainbowcreation.orge.section.SectionData.CELLS];
        NeighborHalo halo = HaloAssembler.assemble(null, null, null, null, null, null);
        return new StepTask(new SubchunkKey(0, 0, 0), m, mass, t, halo);
    }

    private static ThermalWorld.Batch oneSectionBatch(float fill) {
        StepTask st = task(fill);
        return new ThermalWorld.Batch(
                List.of(new ThermalWorld.BatchEntry(
                        Identifier.fromNamespaceAndPath("minecraft", "overworld"), st.key(), st)),
                List.of(MaterialLut.VOID));
    }

    private static Worker worker() {
        return new Worker(UUID.randomUUID(), true, 2, 4, 250.0, 3);
    }

    /**
     * A fake engine that adds {@code delta} to every temperature cell ONLY on a conduction pass;
     * the advection pass is identity on temperature (physically correct: bulk mass movement on an
     * identity engine does not change T). Mass is carried through unchanged. This lets the
     * conduction-only assertions below survive the new advection cadence: advection submits do not
     * perturb the temperatures the conduction pass produced.
     */
    private static OrgeEngine deltaEngine(float delta, double millis) {
        return new OrgeEngine() {
            @Override public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
                List<StepResult> out = new ArrayList<>();
                boolean conduction = (passes & PASS_CONDUCTION) != 0;
                for (StepTask t : tasks) {
                    float[] r = t.temperature().clone();
                    if (conduction) {
                        for (int i = 0; i < r.length; i++) r[i] += delta;
                    }
                    out.add(new StepResult(r, t.mass().clone()));
                }
                return out;
            }
            @Override public double lastStepMillis() { return millis; }
        };
    }

    /**
     * Tick {@code s} {@code count} times, completing each submitted step synchronously (set
     * {@code done} before the next tick services the in-flight job). Lets the scheduler return to
     * IDLE between cadence boundaries so a multi-boundary run (e.g. reaching the coincident tick 20)
     * proceeds. {@code runner.done} is left true at the end.
     */
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
        world.batch = oneSectionBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 4; i++) s.onServerTick();
        assertEquals(0, world.snapshots, "no snapshot before the first advection boundary (tick 5)");
        s.onServerTick();
        assertEquals(1, world.snapshots, "snapshot taken at the 5-tick advection boundary");
        assertNotNull(runner.task, "an advection step was submitted");
    }

    @Test
    void conductionStepWritesBackValidatedResultAtTheCoincidentBoundary() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        // Complete every advection boundary synchronously until we reach (and complete) the
        // coincident conduction boundary at tick 20. Ticks 5,10,15 are advection-only (identity
        // T); tick 20 runs conduction (+5) then advection (identity T) -> writes 305.
        tickCompleting(s, runner, 21);

        float lastT = world.writes.get(world.writes.size() - 1)[0];
        assertEquals(305f, lastT, "conduction input 300 + delta 5, validated, written at tick 20");
        assertTrue(w.onTimeStreak() >= 1, "on-time, under-budget steps advanced the streak");
    }

    @Test
    void nonFiniteResultsKeepFallbackInput() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        // Drive to the first advection submit (tick 5), then hand it a NaN result.
        for (int i = 0; i < 5; i++) s.onServerTick();
        float[] nan = new float[net.rainbowcreation.orge.section.SectionData.CELLS];
        java.util.Arrays.fill(nan, Float.NaN);
        runner.canned = List.of(new StepResult(nan, new float[net.rainbowcreation.orge.section.SectionData.CELLS]));
        runner.done = true;
        s.onServerTick();
        assertEquals(300f, world.writes.get(0)[0], "NaN -> fallback (snapshot input 300)");
    }

    @Test
    void deadlineMissHoldsPreviousThenCancelsAndDropsRange() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        // First advection submit at tick 5; never mark done -> the grace window burns out.
        for (int i = 0; i < 5; i++) s.onServerTick();
        for (int i = 0; i < Scheduler.TICKS_PER_STEP * 2; i++) s.onServerTick();
        assertTrue(runner.cancelled, "step cancelled after the grace window");
        assertEquals(0, world.writes.size(), "no write-back on a missed deadline (held previous)");
        assertEquals(1, w.range(), "late step dropped the range from 2 to 1");
    }

    @Test
    void completionDuringGraceCountsAsLate() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        // Submit at tick 5; let TICKS_PER_STEP ticks pass (into the grace window) then finish.
        for (int i = 0; i < 5; i++) s.onServerTick();
        for (int i = 0; i < Scheduler.TICKS_PER_STEP; i++) s.onServerTick();
        runner.done = true;
        s.onServerTick();
        assertEquals(1, world.writes.size(), "a late-but-finished step still writes back");
        assertEquals(1, w.range(), "but it counts as late -> range dropped");
    }

    @Test
    void engineFailureHoldsPreviousAndDropsRange() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 5; i++) s.onServerTick();
        runner.failure = new RuntimeException("engine boom");
        runner.done = true;
        s.onServerTick();
        assertEquals(0, world.writes.size(), "failed step writes nothing");
        assertEquals(1, w.range(), "failed step treated as late");
    }

    @Test
    void emptyBatchDoesNotSubmitAndStaysIdle() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = new ThermalWorld.Batch(List.of(), List.of(MaterialLut.VOID));
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 40; i++) s.onServerTick();
        assertNull(runner.task, "nothing submitted for an empty batch");
        assertTrue(world.snapshots >= 1, "but it still tried to snapshot at a cadence boundary");
    }

    @Test
    void phaseChangeRunsForEachWrittenEntryOnTheConductionBoundary() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        RecordingPhaseChanger phase = new RecordingPhaseChanger();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker(), phase);

        // Reach + complete the coincident conduction boundary (tick 20); phase change runs there.
        tickCompleting(s, runner, 21);

        assertTrue(phase.applied.contains(new SubchunkKey(0, 0, 0)),
                "phase change applied for the written section at the conduction boundary");
    }

    @Test
    void phaseChangeDoesNotRunWhenTheConductionStepFails() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        RecordingPhaseChanger phase = new RecordingPhaseChanger();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker(), phase);

        // Complete advection boundaries until tick 20 is the next serviced completion, then fail it.
        for (int i = 0; i < 19; i++) { runner.done = true; s.onServerTick(); }
        // Tick 20 submits conduction+advection; fail its result.
        s.onServerTick();          // tick 20: submit (state AWAITING)
        runner.failure = new RuntimeException("boom");
        runner.done = true;
        int before = phase.applied.size();
        s.onServerTick();          // service the coincident step -> fails
        assertEquals(before, phase.applied.size(), "no phase change on a failed conduction step");
    }

    @Test
    void frozenTicksDoNotAdvanceOrSubmit() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        // /tick freeze: SERVER_POST still fires, but game ticks are not advancing.
        for (int i = 0; i < 40; i++) s.onServerTick(false);
        assertEquals(0, world.snapshots, "no snapshot while game ticks are frozen");
        assertNull(runner.task, "nothing submitted while frozen");

        // Resume: the cadence picks up; the first advection boundary is tick 5.
        for (int i = 0; i < 5; i++) s.onServerTick(true);
        assertEquals(1, world.snapshots, "stepping resumes once ticks advance again");
        assertNotNull(runner.task, "a step is submitted after the freeze lifts");
    }

    @Test
    void longFreezeDoesNotCancelAnInFlightStep() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 5; i++) s.onServerTick(true); // submit a step at tick 5
        // Freeze far longer than the grace window while the step is still running.
        for (int i = 0; i < Scheduler.TICKS_PER_STEP * 5; i++) s.onServerTick(false);
        assertFalse(runner.cancelled, "a freeze must not burn the in-flight grace window");
        assertEquals(0, world.writes.size(), "no write-back until the frozen step completes");

        runner.done = true;
        s.onServerTick(true);
        assertEquals(1, world.writes.size(), "the step writes back once ticks resume");
        assertEquals(1, w.onTimeStreak(), "and counts as on-time (freeze did not consume the deadline)");
    }

    @Test
    void noArgTickStillAdvances() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 5; i++) s.onServerTick();
        assertEquals(1, world.snapshots, "the no-arg overload advances as before (gameAdvancing=true)");
    }

    @Test
    void phaseChangeDoesNotRunWhenTheDeadlineIsMissedAndCancelled() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        RecordingPhaseChanger phase = new RecordingPhaseChanger();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker(), phase);

        for (int i = 0; i < 5; i++) s.onServerTick();
        for (int i = 0; i < Scheduler.TICKS_PER_STEP * 2; i++) s.onServerTick();

        assertTrue(phase.applied.isEmpty(), "no phase change when the step is cancelled");
    }
}
