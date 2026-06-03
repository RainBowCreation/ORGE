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
    public static final double ADVECTION_DT_SECONDS = 0.25;

    /** Max simulated seconds a single combined step may catch up (spec 2026-06-02 B4). Bounds
     *  per-job work so catch-up cannot spiral; beyond it, real-time debt is dropped (graceful
     *  slow-motion). Audit-tunable. */
    public static final double MAX_CATCHUP_SECONDS = 0.5;

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
    private StepRunner.Handle pending;

    /** The in-flight cycle's column inputs (captured at submit) + the engine's per-column outputs
     *  (set inside the runner task before it returns; read by {@link #complete} after isDone()). The
     *  StepRunner stays typed to {@link StepResult}; the column results ride this field, made visible by
     *  the Future happens-before in {@code pending.result()}. */
    private List<ThermalWorld.ColumnEntry> pendingColumns;
    private List<ColumnResult> pendingColumnResults;

    /** The in-flight job's injection-aware engine result (set inside the runner task when injections
     *  were submitted; null on a 4-arg/empty cycle). Carries the per-species placement ledger deltas
     *  ({@code injected}/{@code sealedLoss}) the §9 gate declares via {@code ledger.expect(...)}. */
    private net.rainbowcreation.orge.engine.RegionStepResult pendingRegionResult;
    /** The intents drained for this cycle's batch (captured at submit). Cleared from the queue only on
     *  a successful, non-held write-back (durability: a HELD region leaves them queued for next try). */
    private List<PendingInjections.Intent> pendingDrained = List.of();

    /** Whether the in-flight job ran advection (captured at submit), to pick the writeback set. */
    private boolean pendingAdvection;
    /** Batch material table (captured at submit): the region-wide §9 ledger keys species off it. */
    private List<Material> pendingMaterials;

    /** Server-thread nanos spent in {@code world.snapshot(...)} for the in-flight cycle; summed
     *  with the {@code complete()} body and fed to the health throttle (NOT the native step). */
    private long pendingSnapshotNanos;

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
        boolean boundary = (tickCounter % ADVECTION_TICKS == 0);
        if (state == State.AWAITING) {
            ticksSinceSubmit++;
            if (pending.isDone()) {
                // A step finishing within the grace window still writes back (counted late if it
                // overran the deadline); the cancel below only fires for a step STILL not done.
                complete(ticksSinceSubmit <= TICKS_PER_STEP);
            } else if (ticksSinceSubmit >= TICKS_PER_STEP * 2) {
                pending.cancel();
                worker.reportLate();
                toIdle();
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
        // Measure the server-thread cost of assembling the column snapshot (active+apron, full-height
        // columns): a dominant per-cycle cost ON the server thread that must feed the health throttle.
        long snapStart = System.nanoTime();
        ThermalWorld.ColumnBatch batch = world.snapshotColumns(worker.range());
        pendingSnapshotNanos = System.nanoTime() - snapStart;
        if (batch.entries().isEmpty()) {
            return; // stay IDLE; nothing to simulate this step
        }
        List<ColumnTask> input = new ArrayList<>(batch.entries().size());
        for (ThermalWorld.ColumnEntry e : batch.entries()) input.add(e.task());
        List<Material> lut = batch.lut();
        pendingMaterials = lut;
        pendingAdvection = true;
        pendingColumns = batch.entries();
        pendingColumnResults = null;
        pendingRegionResult = null;
        // This cycle's placements: displace-and-inject into the engine + the intents they came from
        // (cleared from the queue only on a successful write-back, see writeBackColumns).
        final List<net.rainbowcreation.orge.engine.EngineInjection> injections = batch.injections();
        pendingDrained = batch.drained();
        final double dt = nextDt();
        pending = runner.submit(() -> {
            // ONE combined call: orgeStepWorld sub-cycles n=round(dt/0.25) interleaved
            // conduction(sub_dt) -> advection(sub_dt) sub-steps (spec 2026-06-02 A5/B1). The
            // 5-arg overload applies this cycle's injections once before advection and returns the
            // per-species placement ledger (injected/sealedLoss) for the §9 gate to declare.
            if (input.isEmpty()) {
                pendingColumnResults = List.of();
            } else {
                net.rainbowcreation.orge.engine.RegionStepResult rr =
                        engine.stepWorld(input, lut, dt,
                                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION, injections);
                pendingRegionResult = rr;
                pendingColumnResults = rr.columns();
            }
            return List.of();
        });
        ticksSinceSubmit = 0;
        ticksSinceLastDispatch = 0; // reset at dispatch: a blocked-submit overrun window inflates the next catch-up dt
        state = State.AWAITING;
    }

    /** Simulated seconds for the next combined step: real time since the last dispatch (ticks/20),
     *  floored at the base quantum and capped at the catch-up ceiling (spec 2026-06-02 B4). */
    private double nextDt() {
        double secs = ticksSinceLastDispatch / 20.0;      // real seconds since the last dispatched step
        if (secs < ADVECTION_DT_SECONDS) secs = ADVECTION_DT_SECONDS;
        if (secs > MAX_CATCHUP_SECONDS)  secs = MAX_CATCHUP_SECONDS;
        return secs;
    }

    private void complete(boolean metDeadline) {
        // Time the server-thread phase of this cycle (write-back + §9 + phase/reconcile). Summed
        // with the snapshot time measured in submit() to give the per-cycle server-thread cost,
        // which feeds the health throttle (NOT engine.lastStepMillis(), the off-thread native step).
        long completeStart = System.nanoTime();
        try {
            pending.result(); // propagates a step failure; column results ride pendingColumnResults
        } catch (RuntimeException e) {
            // A failed step never corrupts the store: hold previous values, treat as late.
            LOGGER.warn("[ORGE] simulation step failed; holding previous values", e);
            worker.reportLate();
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
            toIdle();
        }
    }

    /**
     * Validate the whole region's columns once (region-wide per-species §9 ledger) and write each back.
     * Mirrors the old advection-batch HOLD semantics at region granularity: a cross-column transfer
     * cancels in the region sum (the donor column's after-sum drops, the recipient's rises), so a fall
     * or X/Z flow between two co-stepped columns is accepted while genuine fabrication is rejected. On a
     * non-conserving region NOTHING is written this cycle (atomic HOLD). Runs on the server thread; its
     * wall-time is part of the per-cycle budget the throttle measures.
     */
    private void writeBackColumns(boolean metDeadline) {
        List<ColumnResult> results = pendingColumnResults;
        if (results == null) {
            return; // step produced no results (e.g. empty input) — nothing to write
        }
        if (results.size() < pendingColumns.size()) {
            LOGGER.warn("[ORGE] engine returned {} columns for {} requested; trailing columns hold previous values",
                    results.size(), pendingColumns.size());
        }
        int n = Math.min(results.size(), pendingColumns.size());
        if (!pendingAdvection) {
            // Conduction-only cycle (none submitted today, but the cadence is audit-tunable): T-only,
            // mass carried through. No conservation gate needed — conduction moves no mass.
            for (int i = 0; i < n; i++) {
                world.writeBackColumn(pendingColumns.get(i), results.get(i));
            }
            return;
        }
        // Region-wide per-species conservation: feed EVERY column's (before, after, inSpecies, outSpecies)
        // through one ledger and decide ONCE. Conserved → write each column back; else HOLD the whole region.
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        for (int i = 0; i < n; i++) {
            ThermalWorld.ColumnEntry e = pendingColumns.get(i);
            ColumnResult r = results.get(i);
            ledger.add(r.mass(), e.task().mass(), e.task().matIx(), r.matIx(), pendingMaterials);
        }
        if (pendingRegionResult != null) {
            // A4: declare this cycle's placement deltas so a legit injection (after-before = injected
            // - sealedLoss) does NOT read as fabrication and HOLD the region.
            ledger.expect(pendingRegionResult.injected(), pendingRegionResult.sealedLoss());
        }
        if (!ledger.conserved()) {
            LOGGER.warn("[ORGE] region step mass not conserved (per-species); holding {} columns this cycle", n);
            return; // HELD — drained intents stay queued for the next try (durability)
        }
        for (int i = 0; i < n; i++) {
            world.writeBackColumn(pendingColumns.get(i), results.get(i));
        }
        if (!pendingDrained.isEmpty()) {
            // Success ⇒ the placements are now durably in the store; clear them from the queue.
            world.pendingInjections().remove(pendingDrained);
            pendingDrained = List.of();
        }
    }


    private void toIdle() {
        pending = null;
        pendingColumns = null;
        pendingColumnResults = null;
        // Drop the injection-aware result so a subsequent conduction-only/empty cycle can't reuse stale
        // placement deltas. pendingDrained is NOT cleared here: when a region HOLDs (or a step fails)
        // the intents must survive — the next submit re-drains them from the still-populated queue.
        pendingRegionResult = null;
        ticksSinceSubmit = 0;
        state = State.IDLE;
        // tickCounter is NOT reset here: it free-runs on the 5-tick cadence grid so the cadences
        // stay on the same phase regardless of how long the previous step took.
    }
}
