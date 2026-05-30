package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
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
        Callable<List<float[]>> task;
        boolean done;
        boolean cancelled;
        List<float[]> canned;
        RuntimeException failure;

        @Override
        public Handle submit(Callable<List<float[]>> t) {
            this.task = t;
            this.done = false;
            this.cancelled = false;
            this.canned = null;
            this.failure = null;
            return new Handle() {
                @Override public boolean isDone() { return done; }
                @Override public List<float[]> result() {
                    if (failure != null) throw failure;
                    if (canned != null) return canned;
                    try { return task.call(); } catch (Exception e) { throw new RuntimeException(e); }
                }
                @Override public void cancel() { cancelled = true; }
            };
        }
    }

    private static final class FakeWorld implements ThermalWorld {
        Batch batch;
        final List<float[]> writes = new ArrayList<>();
        final List<SubchunkKey> writeKeys = new ArrayList<>();
        int snapshots;

        @Override public Batch snapshot(int range) { snapshots++; return batch; }
        @Override public void writeBack(BatchEntry entry, float[] t) {
            writeKeys.add(entry.key());
            writes.add(t);
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

    private static OrgeEngine deltaEngine(float delta, double millis) {
        return new OrgeEngine() {
            @Override public List<float[]> step(List<StepTask> tasks, List<Material> lut, double dt) {
                List<float[]> out = new ArrayList<>();
                for (StepTask t : tasks) {
                    float[] r = t.temperature().clone();
                    for (int i = 0; i < r.length; i++) r[i] += delta;
                    out.add(r);
                }
                return out;
            }
            @Override public double lastStepMillis() { return millis; }
        };
    }

    @Test
    void submitsOnlyEveryTwentyTicks() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 19; i++) s.onServerTick();
        assertEquals(0, world.snapshots, "no snapshot before tick 20");
        s.onServerTick();
        assertEquals(1, world.snapshots, "snapshot taken at the step boundary");
        assertNotNull(runner.task, "a step was submitted");
    }

    @Test
    void writesBackValidatedResultsWhenDone_andRecordsOnTimeStep() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 20; i++) s.onServerTick();
        runner.done = true;
        s.onServerTick();
        assertEquals(1, world.writes.size());
        assertEquals(305f, world.writes.get(0)[0], "input 300 + delta 5, validated");
        assertEquals(1, w.onTimeStreak(), "on-time, under-budget step advanced the streak");
    }

    @Test
    void nonFiniteResultsKeepFallbackInput() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 20; i++) s.onServerTick();
        float[] nan = new float[net.rainbowcreation.orge.section.SectionData.CELLS];
        java.util.Arrays.fill(nan, Float.NaN);
        runner.canned = List.of(nan);
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

        for (int i = 0; i < 20; i++) s.onServerTick();
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

        for (int i = 0; i < 20; i++) s.onServerTick();
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

        for (int i = 0; i < 20; i++) s.onServerTick();
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
        assertTrue(world.snapshots >= 1, "but it still tried to snapshot at the boundary");
    }
}
