package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-second server thermal scheduler (DESIGN §8), single-node v1: the server is the
 * sole worker. Driven by {@link #onServerTick()} once per server tick, it runs a small
 * IDLE→AWAITING state machine over a single in-flight {@link StepRunner} step.
 *
 * <p>A single combined step fires every {@link #ADVECTION_TICKS} ticks: ONE
 * {@link OrgeEngine#stepWorld} call running conduction + advection together
 * ({@link OrgeEngine#PASS_CONDUCTION} | {@link OrgeEngine#PASS_ADVECTION}). The native engine
 * sub-cycles the dt into DT_CFL quanta and interleaves heat→flow per sub-step internally
 * (spec 2026-06-02 A5/B1), so temperature and mass advance by the same simulated time. The
 * result is then §9 validated, written back, and handed to {@link PhaseChanger} (§7) and
 * {@link FluidReconciler} (mass → render level). Each boundary submits exactly ONE runner job,
 * keeping the single-in-flight machine.</p>
 *
 * <ul>
 *   <li><b>IDLE</b>: count ticks; at each 5-tick cadence boundary ask the {@link ThermalWorld}
 *       for a batch and, if non-empty, submit the matching job to the runner.</li>
 *   <li><b>AWAITING</b>: when the step finishes, validate (§9) and write each result back per
 *       cadence; a result missing the deadline <i>holds previous values</i>, and after a grace
 *       window the step is cancelled. The {@link Worker} health throttle is driven by the
 *       per-cycle <b>server-thread</b> wall-time ORGE consumes (snapshot + write-back/reconcile),
 *       not the off-thread {@code engine.lastStepMillis()} native step.</li>
 * </ul>
 *
 * <p>This class is loader- and Minecraft-free: all world access is behind {@link ThermalWorld}.
 * Confined to the server thread (no internal locking), exactly like the §5 store.</p>
 */
public final class Scheduler {

    /** Conduction cadence + dt (DESIGN §4): one step per real second = every 20 ticks. */
    public static final int TICKS_PER_STEP = 20;
    public static final double STEP_DT_SECONDS = 1.0;

    /** Advection cadence (DESIGN §10 Decision 2): every 5 ticks = 4 Hz, dt = 0.25 s. Decoupled
     *  from the 20-tick conduction cadence; tunable in the in-game audit. */
    public static final int ADVECTION_TICKS = 5;
    /** Default {@code restSteps}: consecutive unchanged steps before the engine freezes a cell. */
    public static final int DEFAULT_REST_STEPS = 20;
    public static final double ADVECTION_DT_SECONDS = 0.25;

    /** Max simulated seconds a single combined step may catch up (spec 2026-06-02 B4). Bounds
     *  per-job work so catch-up cannot spiral; beyond it, real-time debt is dropped (graceful
     *  slow-motion). Audit-tunable. */
    public static final double MAX_CATCHUP_SECONDS = 0.5;

    /** Max seconds one sky pass forces (2× its one-second cadence). The pass is explicit in time, so a long
     *  overrun is not forced in one jump: beyond this the sky's real-time debt is dropped, like the step's
     *  catch-up beyond {@link #MAX_CATCHUP_SECONDS} (issue #51 — started steps now run however long). */
    public static final double MAX_FORCING_SECONDS = 2.0;

    /** Range bounds + health-throttle tunables (DESIGN §8; v1 constants). */
    public static final int DEFAULT_RANGE = 2;
    public static final int MAX_RANGE = 4;
    /**
     * Per-cycle <b>server-thread</b> compute budget (ms) the health throttle backs off against.
     * This is the wall-time ORGE spends ON the server thread each cadence cycle ({@code snapshot}
     * + {@code writeBack}/reconcile/§9), NOT the off-thread native engine step. Kept a modest
     * fraction of the 50 ms game tick so ORGE never becomes the reason the server falls behind;
     * with the throttle measuring the real cost, the range settles where this work ≈ budget instead
     * of climbing to {@link #MAX_RANGE}. Audit-tunable.
     */
    public static final double COMPUTE_BUDGET_MILLIS = 30.0;
    public static final int ON_TIME_TICKS_TO_CLIMB = 5;

    /** While a started step overruns, a warning is logged every this many ticks. */
    static final int SLOW_STEP_LOG_TICKS = 20 * 60;

    private static final Logger LOGGER = LoggerFactory.getLogger("ORGE");

    private enum State { IDLE, AWAITING }

    private final OrgeEngine engine;
    private final ThermalWorld world;
    private final StepRunner runner;
    private final Worker worker;
    private final PhaseChanger phaseChanger;
    private final FluidReconciler fluidReconciler;

    private State state = State.IDLE;
    private int tickCounter;
    private int ticksSinceSubmit;
    /** Real ticks elapsed since the last step was DISPATCHED (reset at submit, not at writeback).
     *  Consumed by {@link #nextDt()} as the next step's simulated dt: on-pace this is the 5-tick
     *  cadence (→ 0.25 s); while an overrun blocks new submits it grows, so the next dispatched
     *  step catches up — clamped to {@link #MAX_CATCHUP_SECONDS}, beyond which debt is dropped. */
    private int ticksSinceLastDispatch;
    /** Real ticks since the last sky forcing pass (spec 2026-10-25 §4): the pass runs at the first IDLE
     *  boundary once a second has elapsed and forces the whole elapsed time. */
    private int ticksSinceForcing;
    private StepRunner.Handle pending;

    /** Runtime step knobs ({@code /orge step}; server-thread, reset on restart). {@code ticksPerStep}
     *  replaces {@link #ADVECTION_TICKS} as the cadence; {@code fixedDt} {@code <= 0} = AUTO (real time). */
    private int ticksPerStep = ADVECTION_TICKS;
    private double fixedDt = 0;
    /** Rest freeze: a cell whose data (composition, energy, momentum, pressure, swap charge) is unchanged for
     *  {@code restSteps} consecutive steps, with every cell within one step's reach, is frozen by the engine;
     *  {@code 0} = off. */
    private int restSteps = DEFAULT_REST_STEPS;
    /** {@code /orge perf} timing metrics; {@code pendingDt} is the in-flight step's simulated dt. */
    private final StepMetrics metrics = new StepMetrics();
    private double pendingDt;

    /**
     * One submitted cycle: written by the step thread while it assembles, steps and prepares the batch, and
     * read by the server thread only once the step's Future is done (its happens-before, taken in
     * {@code pending.result()}, publishes every field). Each submission gets a fresh one, so a step that is
     * cancelled but keeps running can only write into its own abandoned cycle (issue #51).
     */
    private static final class Cycle {
        /** The batch's columns (assembled from the server-thread capture) and its material table, which
         *  the region-wide §9 ledger keys species off. */
        List<ThermalWorld.ColumnEntry> columns;
        List<Material> materials;
        /** The engine's per-column outputs. The StepRunner stays typed to {@link StepResult}; the column
         *  results ride this field. */
        List<ColumnResult> columnResults;
        /** The injection-aware engine result (null on an empty cycle). Carries the per-species placement
         *  ledger deltas ({@code injected}/{@code sealedLoss}) the §9 gate declares via {@code ledger.expect}. */
        net.rainbowcreation.orge.engine.RegionStepResult regionResult;
        /** The intents drained for this batch. Cleared from the queue only on a successful, non-held
         *  write-back (durability: a HELD region leaves them queued for next try). */
        List<PendingInjections.Intent> drained = List.of();
        /** The §9 verdict, and the column write-backs prepared for a conserved region (empty on HOLD). */
        boolean conserved;
        List<ThermalWorld.PreparedWrite> writes = List.of();
        /** Wall-time of the step-thread job (assembly + native step + preparation). */
        long stepThreadNanos;
    }

    /** The in-flight cycle (null while IDLE). */
    private Cycle cycle;

    /** Ticks of sky-pass time the in-flight cycle's capture carries (0 when its pass already ran on the store). */
    private int pendingDeferredForcingTicks;

    /** Whether the in-flight job ran advection (set at submit), to pick the writeback set. */
    private boolean pendingAdvection;

    /** Server-thread nanos spent in {@code world.snapshot(...)} for the in-flight cycle; summed
     *  with the {@code complete()} body and fed to the health throttle (NOT the native step). */
    private long pendingSnapshotNanos;
    /** The same, in server-thread CPU time (the wall time less what the OS ran instead on a loaded host). */
    private long pendingSnapshotCpuNanos;

    /** Per-thread CPU clock for the server-thread CPU metric ({@code -1} where unsupported, which reads as 0). */
    private static final java.lang.management.ThreadMXBean THREADS =
            java.lang.management.ManagementFactory.getThreadMXBean();

    /** Backwards-compatible constructor: no phase change, no reconcile (used by unit tests). */
    public Scheduler(OrgeEngine engine, ThermalWorld world, StepRunner runner, Worker worker) {
        this(engine, world, runner, worker, PhaseChanger.NOOP, FluidReconciler.NOOP);
    }

    /** Backwards-compatible constructor: phase change, default NOOP reconcile. */
    public Scheduler(OrgeEngine engine, ThermalWorld world, StepRunner runner, Worker worker,
                     PhaseChanger phaseChanger) {
        this(engine, world, runner, worker, phaseChanger, FluidReconciler.NOOP);
    }

    public Scheduler(OrgeEngine engine, ThermalWorld world, StepRunner runner, Worker worker,
                     PhaseChanger phaseChanger, FluidReconciler fluidReconciler) {
        this.engine = engine;
        this.world = world;
        this.runner = runner;
        this.worker = worker;
        this.phaseChanger = phaseChanger;
        this.fluidReconciler = fluidReconciler;
    }

    /** Advance the scheduler by one server tick (call from the server-tick hook). */
    public void onServerTick() {
        onServerTick(true);
    }

    /**
     * Advance the scheduler by one server tick, bound to Minecraft's game-tick clock rather than
     * wall-clock time (DESIGN §4/§8). {@code gameAdvancing} is the server tick-rate manager's
     * "are game elements ticking this tick?" verdict: it is {@code false} while the world is
     * frozen ({@code /tick freeze}) and {@code true} during normal play, sprint, and the single
     * ticks of {@code /tick step}. When ticks are frozen we do nothing at all — no counting, no
     * submit, and no draining of an in-flight step's grace window — so the simulation clock
     * pauses with the game and resumes exactly where it left off. Both cadences scale with
     * {@code /tick rate} for free.
     */
    public void onServerTick(boolean gameAdvancing) {
        if (!gameAdvancing) {
            return; // frozen: hold the simulation clock in lockstep with the game
        }
        // tickCounter free-runs; a combined conduction+advection step fires every ADVECTION_TICKS
        // (ticks 5,10,15,20,...). Conduction no longer has its own 20-tick boundary — the engine
        // sub-cycles + interleaves heat→flow internally (spec 2026-06-02 A5/B1).
        tickCounter++;
        ticksSinceLastDispatch++;
        ticksSinceForcing++;
        metrics.onRealTick();
        boolean boundary = (tickCounter % ticksPerStep == 0);
        if (state == State.AWAITING) {
            ticksSinceSubmit++;
            if (pending.isDone()) {
                // A step finishing past the deadline still writes back, counted late (issue #51: however
                // long the native step took, its work is never thrown away).
                complete(ticksSinceSubmit <= TICKS_PER_STEP);
            } else if (!pending.started() && ticksSinceSubmit >= TICKS_PER_STEP * 2 && pending.cancelIfNotStarted()) {
                // Only a step the runner never began is cancelled after the grace window. A started step
                // runs to completion: cancelling cannot stop the native call, it only discards the result
                // while the next submission queues behind it on the single step thread (issue #51).
                worker.reportLate();
                metrics.onCancel();
                recreditSkyPass();
                toIdle();
            } else if (ticksSinceSubmit % SLOW_STEP_LOG_TICKS == 0) {
                LOGGER.warn("[ORGE] simulation step still running after {} ticks; holding previous values until it lands",
                        ticksSinceSubmit);
            }
            return; // never submit in the same tick we serviced an in-flight step
        }
        if (boundary) {
            submit(); // both passes, one combined call
        }
    }

    /**
     * Submit one combined runner job for this cadence boundary: a SINGLE
     * {@code stepWorld(PASS_CONDUCTION | PASS_ADVECTION, dt)} call. The native engine sub-cycles
     * the dt into DT_CFL quanta and interleaves conduction→advection per sub-step internally
     * (spec 2026-06-02 A5/B1), so heat and flow advance by the same simulated time.
     */
    private void submit() {
        // Measure the server-thread cost of this cycle's IDLE work and snapshot capture (wall and CPU time):
        // with the complete() body it is the per-cycle server-thread cost that feeds the health throttle.
        long snapStart = System.nanoTime();
        long snapCpuStart = THREADS.getCurrentThreadCpuTime();
        // Thirsty farmland (spec 2026-10-25-thirsty-farmland §3) drains the store at the same IDLE, pre-snapshot
        // point as the sky forcing below, so the drained water is what this step simulates.
        world.applyHydrationDrain();
        // Sky forcing (spec 2026-10-25 §4) once a second has elapsed, forcing the whole elapsed time, BEFORE
        // the snapshot reads the columns, so this step simulates the forced state. It runs with the capture:
        // the live world runs it on the step thread over the captured columns (issue #51).
        double forcingDt = 0.0;
        if (ticksSinceForcing >= TICKS_PER_STEP) {
            forcingDt = Math.min(ticksSinceForcing / 20.0, MAX_FORCING_SECONDS);
            ticksSinceForcing = 0;
        }
        // Server-thread half of the snapshot: copy what it reads (issue #51). The per-cell assembly, the
        // native step and the per-cell write-back preparation all run on the step thread below.
        ThermalWorld.CapturedColumns captured = world.captureColumns(worker.range(), forcingDt);
        pendingSnapshotNanos = System.nanoTime() - snapStart;
        pendingSnapshotCpuNanos = THREADS.getCurrentThreadCpuTime() - snapCpuStart;
        if (captured.isEmpty()) {
            return; // stay IDLE; nothing to simulate this step
        }
        // A sky pass the capture took along only lands with the write-back: remember its time so a cycle
        // that lands nothing hands it back to the next pass.
        pendingDeferredForcingTicks = captured.defersSkyPass() ? (int) Math.round(forcingDt * 20.0) : 0;
        pendingAdvection = true;
        final boolean advection = pendingAdvection;
        final Cycle c = new Cycle();
        cycle = c;
        final double dt = nextDt();
        final int rest = restSteps;
        pendingDt = dt;
        pending = runner.submit(() -> {
            long jobStart = System.nanoTime();
            ThermalWorld.ColumnBatch batch = captured.assemble();
            c.columns = batch.entries();
            c.materials = batch.lut();
            // This cycle's placements: displace-and-inject into the engine + the intents they came from
            // (cleared from the queue only on a successful write-back, see writeBackColumns).
            final List<net.rainbowcreation.orge.engine.EngineInjection> injections = batch.injections();
            c.drained = batch.drained();
            if (InjectDebug.on() && !injections.isEmpty()) {
                InjectDebug.LOG.info("[dispatch] injections={} drained={} dt={}",
                        injections.size(), c.drained.size(), dt);
            }
            List<ColumnTask> input = new ArrayList<>(batch.entries().size());
            for (ThermalWorld.ColumnEntry e : batch.entries()) input.add(e.task());
            // ONE combined call: orgeStepWorld sub-cycles n=round(dt/0.25) interleaved
            // conduction(sub_dt) -> advection(sub_dt) sub-steps (spec 2026-06-02 A5/B1). The
            // 5-arg overload applies this cycle's injections once before advection and returns the
            // per-species placement ledger (injected/sealedLoss) for the §9 gate to declare.
            if (input.isEmpty()) {
                c.columnResults = List.of();
            } else {
                net.rainbowcreation.orge.engine.RegionStepResult rr =
                        engine.stepWorld(input, batch.lutEpoch(), dt,
                                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION, injections, rest);
                c.regionResult = rr;
                c.columnResults = rr.columns();
            }
            prepareWriteBacks(c, advection);
            c.stepThreadNanos = System.nanoTime() - jobStart;
            return List.of();
        });
        ticksSinceSubmit = 0;
        ticksSinceLastDispatch = 0; // reset at dispatch: a blocked-submit overrun window inflates the next catch-up dt
        state = State.AWAITING;
    }

    private double nextDt() {
        return stepDt(ticksSinceLastDispatch, ticksPerStep, fixedDt);
    }

    /**
     * Simulated seconds for the next combined step (spec 2026-06-02 B4). AUTO ({@code fixedDt <= 0}):
     * real time since the last dispatch (ticks/20), floored at base = ticksPerStep/20 and capped at
     * 2×base — at the default 5 ticks exactly {@link #ADVECTION_DT_SECONDS}/{@link #MAX_CATCHUP_SECONDS}.
     * FIXED F: base = F, scaled by elapsed/ticksPerStep on overrun, same [base, 2×base] clamp. F ≠
     * ticksPerStep/20 is a deliberate time-scale.
     */
    static double stepDt(int ticksSinceLastDispatch, int ticksPerStep, double fixedDt) {
        boolean auto = fixedDt <= 0;
        double base = auto ? ticksPerStep / 20.0 : fixedDt;
        double secs = auto ? ticksSinceLastDispatch / 20.0 : fixedDt * ticksSinceLastDispatch / ticksPerStep;
        if (secs < base) secs = base;
        if (secs > 2 * base) secs = 2 * base;
        return secs;
    }

    public int ticksPerStep() { return ticksPerStep; }

    public StepMetrics metrics() { return metrics; }

    public int range() { return worker.range(); }

    public void setTicksPerStep(int ticks) {
        if (ticks < 1 || ticks > 200) throw new IllegalArgumentException("ticksPerStep must be 1..200: " + ticks);
        ticksPerStep = ticks;
    }

    /** Fixed step dt in seconds, or {@code <= 0} when AUTO (real time). */
    public double fixedDt() { return fixedDt; }

    /** Set a fixed dt (0.01..2.0 s), or {@code <= 0} for AUTO. */
    public void setFixedDt(double seconds) {
        if (seconds > 0 && (seconds < 0.01 || seconds > 2.0)) throw new IllegalArgumentException("stepDt must be 0.01..2.0: " + seconds);
        fixedDt = seconds;
    }

    public int restSteps() { return restSteps; }

    /** Steps a cell must stay unchanged before the engine freezes it (0..10000; 0 = off). */
    public void setRestSteps(int steps) {
        if (steps < 0 || steps > 10000) throw new IllegalArgumentException("restSteps must be 0..10000: " + steps);
        restSteps = steps;
    }

    /** The on-pace dt the current knobs produce (for display). */
    public double onPaceDt() { return stepDt(ticksPerStep, ticksPerStep, fixedDt); }

    private void complete(boolean metDeadline) {
        // Time the server-thread phase of this cycle (installing the prepared write-backs, or the HOLD).
        // Summed with the capture time measured in submit() to give the per-cycle server-thread cost,
        // which feeds the health throttle (NOT engine.lastStepMillis(), the off-thread native step).
        long completeStart = System.nanoTime();
        long completeCpuStart = THREADS.getCurrentThreadCpuTime();
        try {
            pending.result(); // propagates a step failure; publishes the cycle the step thread filled
        } catch (RuntimeException e) {
            // A failed step never corrupts the store: hold previous values, treat as late.
            LOGGER.warn("[ORGE] simulation step failed; holding previous values", e);
            worker.reportLate();
            recreditSkyPass();
            toIdle();
            return;
        }
        // Update health AFTER the write-back, fed the real per-cycle server-thread wall-time
        // (snapshot + this complete body). A try/finally guarantees noteStep still runs even if
        // write-back throws, so a write-back exception can't leave the worker's range/streak stale.
        try {
            writeBackColumns(metDeadline);
        } finally {
            double serverThreadMillis = (pendingSnapshotNanos + (System.nanoTime() - completeStart)) / 1_000_000.0;
            // engine.lastStepMillis() is the off-thread native step; logged for audit only — it
            // does NOT drive the throttle (that would be blind to the dominant server-thread cost).
            worker.noteStep(serverThreadMillis, metDeadline);
            metrics.onStepThread(cycle.stepThreadNanos / 1_000_000.0);
            metrics.onServerCpu((pendingSnapshotCpuNanos + THREADS.getCurrentThreadCpuTime() - completeCpuStart) / 1_000_000.0);
            metrics.onComplete(engine.lastStepMillis(), serverThreadMillis, ticksSinceSubmit, metDeadline, pendingDt);
            toIdle();
        }
    }

    /**
     * Step thread, after the native step (issue #51): validate the whole region's columns once
     * (region-wide per-species §9 ledger) and, for a conserved region, prepare each column's write-back.
     * Mirrors the old advection-batch HOLD semantics at region granularity: a cross-column transfer
     * cancels in the region sum (the donor column's after-sum drops, the recipient's rises), so a fall
     * or X/Z flow between two co-stepped columns is accepted while genuine fabrication is rejected. On a
     * non-conserving region NOTHING is written this cycle (atomic HOLD).
     */
    private void prepareWriteBacks(Cycle c, boolean advection) {
        List<ColumnResult> results = c.columnResults;
        if (results == null || results.isEmpty()) {
            return; // step produced no results (e.g. empty input) — nothing to write
        }
        int n = Math.min(results.size(), c.columns.size());
        List<ThermalWorld.PreparedWrite> writes = new ArrayList<>(n);
        if (advection) {
            // Region-wide per-species conservation: feed EVERY column's (before, after, inSpecies,
            // outSpecies) through one ledger and decide ONCE. Conserved → write each column back; else
            // HOLD the whole region.
            StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
            for (int i = 0; i < n; i++) {
                ThermalWorld.ColumnEntry e = c.columns.get(i);
                ColumnResult r = results.get(i);
                ledger.add(r.mass(), e.task().mass(), e.task().matIx(), r.matIx(), c.materials);
            }
            if (c.regionResult != null) {
                // A4: declare this cycle's placement deltas so a legit injection (after-before = injected
                // - sealedLoss) does NOT read as fabrication and HOLD the region.
                ledger.expect(c.regionResult.injected(), c.regionResult.sealedLoss());
            }
            if (!ledger.conserved()) {
                return; // HELD: nothing prepared, c.conserved stays false
            }
        }
        // A conduction-only cycle (none submitted today, but the cadence is audit-tunable) is T-only with
        // mass carried through: no conservation gate needed — conduction moves no mass.
        for (int i = 0; i < n; i++) {
            writes.add(world.prepareWriteBack(c.columns.get(i), results.get(i), c.materials));
        }
        c.conserved = true;
        c.writes = writes;
    }

    /**
     * Server thread: install the write-backs the step thread prepared, or HOLD the region the §9 ledger
     * rejected. Runs on the server thread; its wall-time is part of the per-cycle budget the throttle
     * measures.
     */
    private void writeBackColumns(boolean metDeadline) {
        Cycle c = cycle;
        List<ColumnResult> results = c.columnResults;
        if (results == null || c.columns == null) {
            return; // step produced no results (e.g. empty input) — nothing to write
        }
        if (results.size() < c.columns.size()) {
            LOGGER.warn("[ORGE] engine returned {} columns for {} requested; trailing columns hold previous values",
                    results.size(), c.columns.size());
        }
        int n = Math.min(results.size(), c.columns.size());
        if (n == 0) {
            return;
        }
        if (!c.conserved) {
            if (InjectDebug.on() && !c.drained.isEmpty()) {
                InjectDebug.LOG.info("[gate] HELD conserved=false injected={} sealedLoss={} keptIntents={}",
                        c.regionResult != null ? InjectDebug.nonzero(c.regionResult.injected()) : "[]",
                        c.regionResult != null ? InjectDebug.nonzero(c.regionResult.sealedLoss()) : "[]",
                        c.drained.size());
            }
            LOGGER.warn("[ORGE] region step mass not conserved (per-species); holding {} columns this cycle", n);
            metrics.onHeld();
            recreditSkyPass();
            return; // HELD — drained intents stay queued for the next try (durability)
        }
        for (ThermalWorld.PreparedWrite w : c.writes) {
            w.apply();
        }
        if (pendingAdvection && !c.drained.isEmpty()) {
            if (InjectDebug.on()) {
                InjectDebug.LOG.info("[gate] OK wrote={} injected={} sealedLoss={} clearedIntents={}",
                        n, c.regionResult != null ? InjectDebug.nonzero(c.regionResult.injected()) : "[]",
                        c.regionResult != null ? InjectDebug.nonzero(c.regionResult.sealedLoss()) : "[]",
                        c.drained.size());
            }
            // Success ⇒ the placements are now durably in the store; clear them from the queue.
            world.pendingInjections().remove(c.drained);
        }
    }

    /**
     * Server stop: drop the in-flight cycle. Its result belongs to the stopping world; in singleplayer the
     * scheduler outlives the server, and the next world's first tick would otherwise install it there.
     */
    public void reset() {
        if (pending != null) {
            pending.cancel();
        }
        toIdle();
    }

    /** The in-flight cycle lands nothing: a sky pass its capture carried is handed back to the next pass. */
    private void recreditSkyPass() {
        ticksSinceForcing += pendingDeferredForcingTicks;
        pendingDeferredForcingTicks = 0;
    }

    private void toIdle() {
        pending = null;
        // The cycle goes with the step: a HELD region's intents were never removed from the queue, so the
        // next submit re-drains them from it.
        cycle = null;
        ticksSinceSubmit = 0;
        state = State.IDLE;
        // tickCounter is NOT reset here: it free-runs on the 5-tick cadence grid so the cadences
        // stay on the same phase regardless of how long the previous step took.
    }
}
