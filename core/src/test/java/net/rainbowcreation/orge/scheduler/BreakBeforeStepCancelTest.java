package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.EngineInjection;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #65 — a BREAK drained into a step, then a same-species re-place while that step is in flight.
 * The re-place cancels the queued removal (the cell keeps its block), but the in-flight step already
 * carries the vacuum stomp. If that step is then written back, the store holds {@code orge:vacuum} at
 * 0 kg under the re-placed block — a vacuum cell neighbours flow through. If the step is cancelled
 * instead, nothing is written and the cancel was right. Drives the real {@link Scheduler},
 * {@link PendingInjections}, {@link InjectionDrain} and {@link BlockChangeCapture} capture step.
 */
class BreakBeforeStepCancelTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Material STONE = TestMaterials.stone();
    private static final List<Material> LUT = List.of(MaterialLut.VACUUM, STONE);
    private static final int CELL = 3 + 16 * 70 + 6144 * 4;

    /** Runner the test completes or lets time out. */
    private static final class HeldRunner implements StepRunner {
        boolean done;
        boolean cancelled;
        @Override public Handle submit(Callable<List<StepResult>> t) {
            done = false;
            return new Handle() {
                @Override public boolean isDone() { return done; }
                @Override public List<StepResult> result() {
                    try { return t.call(); } catch (Exception e) { throw new RuntimeException(e); }
                }
                @Override public void cancel() { cancelled = true; }
            };
        }
    }

    /** One column of stone (2000 kg/cell); the snapshot drains the live queue through the real drain. */
    private static final class QueueWorld implements ThermalWorld {
        final PendingInjections queue = new PendingInjections();
        final List<ColumnResult> written = new ArrayList<>();
        @Override public PendingInjections pendingInjections() { return queue; }
        @Override public ColumnBatch snapshotColumns(int range) {
            int n = RegionMarshaller.CHUNK_N;
            char[] m = new char[n];
            float[] mass = new float[n];
            float[] t = new float[n];
            Arrays.fill(m, (char) 1);
            Arrays.fill(mass, STONE.defaultMass());
            Arrays.fill(t, 290f);
            List<EngineInjection> injections = new ArrayList<>();
            List<PendingInjections.Intent> drained = new ArrayList<>();
            InjectionDrain.applyToColumn(0, m, mass, id -> (char) (STONE.id().equals(id) ? 1 : 0),
                    queue.peekColumn(DIM, 0, 0), cell -> STONE.id(), cell -> STONE.defaultMass(),
                    injections, drained);
            ColumnEntry e = new ColumnEntry(DIM, 0, 0, new ColumnTask(0, 0, m, mass, t));
            return new ColumnBatch(List.of(e), LUT, 0, injections, drained);
        }
        @Override public void writeBackColumn(ColumnEntry entry, ColumnResult result) {
            written.add(result);
        }
    }

    /** Conserving engine: every cell keeps its input state. */
    private static final OrgeEngine IDENTITY = new OrgeEngine() {
        @Override public void registerMaterials(int lutEpoch, List<Material> table) { }
        @Override public List<ColumnResult> stepWorld(List<ColumnTask> columns, int lutEpoch, double dt, int passes) {
            List<ColumnResult> out = new ArrayList<>();
            for (ColumnTask c : columns) {
                out.add(new ColumnResult(c.matIx().clone(), c.mass().clone(), c.temperature().clone()));
            }
            return out;
        }
        @Override public double lastStepMillis() { return 1.0; }
    };

    private static Scheduler scheduler(QueueWorld world, HeldRunner runner) {
        return new Scheduler(IDENTITY, world, runner, new Worker(UUID.randomUUID(), true, 2, 4, 1000.0, 5));
    }

    /** BREAK, the step drains the removal, the block is re-placed, then the step is written back. */
    @Test
    void rePlaceDuringAStepThatAppliedTheBreakRevivesTheBlockAfterTheWriteBack() {
        QueueWorld world = new QueueWorld();
        HeldRunner runner = new HeldRunner();
        Scheduler s = scheduler(world, runner);

        world.queue.enqueueRemoval(DIM, 0, 0, CELL);            // BREAK
        for (int i = 0; i < 5; i++) s.onServerTick();            // submit: the drain stomps CELL to vacuum
        BlockChangeCapture.captureOrCancelStaleRemoval(          // re-place the same stone mid-step
                world.queue, DIM, 0, 0, CELL, STONE, STONE, 295f);
        runner.done = true;
        s.onServerTick();                                        // the step completes and is written back

        assertEquals(1, world.written.size(), "the step was written back");
        assertEquals(0, world.written.get(0).matIx()[CELL], "the written step carries the break's vacuum");
        List<PendingInjections.Intent> next = world.queue.peekColumn(DIM, 0, 0);
        assertEquals(1, next.size(), "the re-placed block is queued again: the store must not stay vacuum");
        assertFalse(next.get(0).removal());
        assertEquals(STONE.id(), next.get(0).species());
        assertEquals(STONE.defaultMass(), next.get(0).mass());
    }

    /** BREAK, the step drains the removal, the block is re-placed, then the step is cancelled. */
    @Test
    void rePlaceDuringAStepThatIsCancelledQueuesNothing() {
        QueueWorld world = new QueueWorld();
        HeldRunner runner = new HeldRunner();
        Scheduler s = scheduler(world, runner);

        world.queue.enqueueRemoval(DIM, 0, 0, CELL);
        for (int i = 0; i < 5; i++) s.onServerTick();
        BlockChangeCapture.captureOrCancelStaleRemoval(
                world.queue, DIM, 0, 0, CELL, STONE, STONE, 295f);
        for (int i = 0; i < Scheduler.TICKS_PER_STEP * 2; i++) s.onServerTick();

        assertTrue(runner.cancelled, "the step was cancelled");
        assertTrue(world.written.isEmpty(), "a cancelled step writes nothing: the store keeps the stone");
        assertTrue(world.queue.peekColumn(DIM, 0, 0).isEmpty(),
                "nothing to revive — re-injecting would put a second block's mass into the cell");

        runner.done = false;
        for (int i = 0; i < 5; i++) s.onServerTick();            // the next step stays stone
        runner.done = true;
        s.onServerTick();
        assertEquals(1, world.written.size());
        assertEquals(1, world.written.get(0).matIx()[CELL], "the next step keeps the stone");
        assertEquals(STONE.defaultMass(), world.written.get(0).mass()[CELL]);
        assertTrue(world.queue.peekColumn(DIM, 0, 0).isEmpty());
    }

    /** A second BREAK after the re-place wins over the revival: the cell stays broken. */
    @Test
    void aLaterBreakSupersedesTheRevival() {
        QueueWorld world = new QueueWorld();
        HeldRunner runner = new HeldRunner();
        Scheduler s = scheduler(world, runner);

        world.queue.enqueueRemoval(DIM, 0, 0, CELL);
        for (int i = 0; i < 5; i++) s.onServerTick();
        BlockChangeCapture.captureOrCancelStaleRemoval(
                world.queue, DIM, 0, 0, CELL, STONE, STONE, 295f);
        world.queue.enqueueRemoval(DIM, 0, 0, CELL);            // broken again, still mid-step
        runner.done = true;
        s.onServerTick();

        assertEquals(1, world.written.size());
        assertTrue(world.queue.peekColumn(DIM, 0, 0).stream().noneMatch(in -> !in.removal()),
                "no stone placement is revived over the second break");
    }
}
