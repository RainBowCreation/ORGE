package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.phase.PhaseChanger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The per-second server conduction scheduler (DESIGN §8), single-node v1: the server is the
 * sole worker. Driven by {@link #onServerTick()} once per server tick, it runs a small
 * IDLE→AWAITING state machine over a single in-flight {@link StepRunner} step.
 *
 * <ul>
 *   <li><b>IDLE</b>: count ticks; at {@link #TICKS_PER_STEP} ask the {@link ThermalWorld} for a
 *       batch and, if non-empty, submit {@code engine.step(...)} to the runner.</li>
 *   <li><b>AWAITING</b>: when the step finishes, validate (§9) and write each result back; a
 *       result missing the 1 s deadline <i>holds previous temps</i>, and after a grace window
 *       the step is cancelled. {@code engine.lastStepMillis()} drives the {@link Worker}
 *       health throttle.</li>
 * </ul>
 *
 * <p>This class is loader- and Minecraft-free: all world access is behind {@link ThermalWorld}.
 * Confined to the server thread (no internal locking), exactly like the §5 store.</p>
 */
public final class Scheduler {

    /** dt and cadence (DESIGN §4): one step per real second = every 20 ticks. */
    public static final int TICKS_PER_STEP = 20;
    public static final double STEP_DT_SECONDS = 1.0;

    /** Range bounds + health-throttle tunables (DESIGN §8; v1 constants). */
    public static final int DEFAULT_RANGE = 2;
    public static final int MAX_RANGE = 4;
    public static final double COMPUTE_BUDGET_MILLIS = 250.0;
    public static final int ON_TIME_TICKS_TO_CLIMB = 5;

    private static final Logger LOGGER = LoggerFactory.getLogger("ORGE");

    private enum State { IDLE, AWAITING }

    private final OrgeEngine engine;
    private final ThermalWorld world;
    private final StepRunner runner;
    private final Worker worker;
    private final PhaseChanger phaseChanger;

    private State state = State.IDLE;
    private int tickCounter;
    private int ticksSinceSubmit;
    private StepRunner.Handle pending;
    private List<ThermalWorld.BatchEntry> pendingEntries;

    /** Backwards-compatible constructor: no phase change (used by unit tests). */
    public Scheduler(OrgeEngine engine, ThermalWorld world, StepRunner runner, Worker worker) {
        this(engine, world, runner, worker, PhaseChanger.NOOP);
    }

    public Scheduler(OrgeEngine engine, ThermalWorld world, StepRunner runner, Worker worker,
                     PhaseChanger phaseChanger) {
        this.engine = engine;
        this.world = world;
        this.runner = runner;
        this.worker = worker;
        this.phaseChanger = phaseChanger;
    }

    /** Advance the scheduler by one server tick (call from the server-tick hook). */
    public void onServerTick() {
        boolean boundary = (++tickCounter >= TICKS_PER_STEP);
        if (boundary) {
            tickCounter = 0;
        }
        if (state == State.AWAITING) {
            ticksSinceSubmit++;
            if (pending.isDone()) {
                // A step finishing exactly at the grace boundary still writes back (counted
                // late); the cancel below only fires for a step that is STILL not done.
                complete(ticksSinceSubmit <= TICKS_PER_STEP);
            } else if (ticksSinceSubmit >= TICKS_PER_STEP * 2) {
                pending.cancel();
                worker.reportLate();
                toIdle();
            }
            return; // never submit in the same tick we serviced an in-flight step
        }
        if (boundary) {
            submit();
        }
    }

    private void submit() {
        ThermalWorld.Batch batch = world.snapshot(worker.range());
        if (batch.entries().isEmpty()) {
            return; // stay IDLE; nothing to simulate this step
        }
        List<StepTask> tasks = batch.entries().stream().map(ThermalWorld.BatchEntry::task).toList();
        pending = runner.submit(() -> engine.step(tasks, batch.lut(), STEP_DT_SECONDS));
        pendingEntries = batch.entries();
        ticksSinceSubmit = 0;
        state = State.AWAITING;
    }

    private void complete(boolean metDeadline) {
        List<float[]> results;
        try {
            results = pending.result();
        } catch (RuntimeException e) {
            // A failed step never corrupts the store: hold previous temps, treat as late.
            LOGGER.warn("[ORGE] conduction step failed; holding previous temps", e);
            worker.reportLate();
            toIdle();
            return;
        }
        // Update health from this step BEFORE writing back, so a write-back exception can't
        // leave the worker's range/streak stale.
        worker.noteStep(engine.lastStepMillis(), metDeadline);
        if (results.size() < pendingEntries.size()) {
            LOGGER.warn("[ORGE] engine returned {} results for {} sections; trailing sections hold previous temps",
                    results.size(), pendingEntries.size());
        }
        int n = Math.min(results.size(), pendingEntries.size());
        for (int i = 0; i < n; i++) {
            ThermalWorld.BatchEntry entry = pendingEntries.get(i);
            float[] cleaned = StepValidator.clean(results.get(i), entry.task().temperature());
            world.writeBack(entry, cleaned);
            phaseChanger.applyPhaseChanges(entry);
        }
        toIdle();
    }

    private void toIdle() {
        pending = null;
        pendingEntries = null;
        ticksSinceSubmit = 0;
        state = State.IDLE;
        // tickCounter is NOT reset here: it free-runs on the global 20-tick grid so the step
        // cadence stays ~1 Hz regardless of how long the previous step took.
    }
}
