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
 * <p>Two decoupled cadences (DESIGN §10 Decision 2) ride the free-running {@link #tickCounter}
 * on the global 20-tick grid:</p>
 * <ul>
 *   <li><b>Conduction</b> fires on the {@link #TICKS_PER_STEP}-tick boundary ({@code dt = 1.0 s},
 *       {@link OrgeEngine#PASS_CONDUCTION}): within-cell heat exchange, then §9 validate(T),
 *       writeBack(T), {@link PhaseChanger} (§7).</li>
 *   <li><b>Advection</b> fires every {@link #ADVECTION_TICKS} ticks ({@code dt = 0.25 s},
 *       {@link OrgeEngine#PASS_ADVECTION}): bulk mass movement, then §9 validate(+Σmass),
 *       writeBack(T + mass), {@link FluidReconciler} (mass → render level).</li>
 * </ul>
 *
 * <p>Each cadence boundary submits exactly ONE runner job (keeping the single-in-flight machine).
 * On the coincident 20-tick boundary the job runs conduction first, then advection ON the
 * post-conduction temperature field — within-cell heat exchange precedes bulk mass movement
 * (documented coincident-tick order). Off the 20-tick boundary (ticks 5,10,15) the job runs
 * advection only.</p>
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
    private StepRunner.Handle pending;

    /** The in-flight cycle's column inputs (captured at submit) + the engine's per-column outputs
     *  (set inside the runner task before it returns; read by {@link #complete} after isDone()). The
     *  StepRunner stays typed to {@link StepResult}; the column results ride this field, made visible by
     *  the Future happens-before in {@code pending.result()}. */
    private List<ThermalWorld.ColumnEntry> pendingColumns;
    private List<ColumnResult> pendingColumnResults;

    /** Which passes the in-flight job ran (captured at submit), to pick the writeback set. */
    private boolean pendingConduction;
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
        // tickCounter free-runs 1..TICKS_PER_STEP on the global 20-tick grid. The advection
        // cadence fires when it is a multiple of ADVECTION_TICKS (ticks 5,10,15,20); conduction
        // fires on the 20-tick boundary (tick 20), which is also an advection boundary.
        tickCounter++;
        boolean conductionBoundary = (tickCounter >= TICKS_PER_STEP);
        boolean advectionBoundary = (tickCounter % ADVECTION_TICKS == 0);
        if (conductionBoundary) {
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
        if (advectionBoundary) {
            // ONE job per cadence boundary. On the coincident 20-tick boundary it runs conduction
            // first then advection (documented order); otherwise advection only.
            submit(conductionBoundary, true);
        }
    }

    /**
     * Submit one runner job for this cadence boundary. {@code conduction}/{@code advection} flag
     * which passes the job runs. On the coincident tick the job runs conduction (dt = 1.0) first,
     * then advection (dt = 0.25) ON the post-conduction temperatures; otherwise advection only.
     */
    private void submit(boolean conduction, boolean advection) {
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
        pendingConduction = conduction;
        pendingAdvection = advection;
        pendingColumns = batch.entries();
        pendingColumnResults = null;
        pending = runner.submit(() -> {
            // Whole-region World step (DESIGN 2026-06-01 §4/§7). Conduction then advection on the
            // post-conduction field, honouring the existing cadence flags. Results ride a field (the
            // runner is typed to StepResult); the empty StepResult list satisfies that contract.
            List<ColumnResult> current = input.isEmpty() ? List.of() : null;
            List<ColumnTask> stepInput = input;
            if (conduction) {
                current = engine.stepWorld(stepInput, lut, STEP_DT_SECONDS, OrgeEngine.PASS_CONDUCTION);
                if (advection) {
                    stepInput = withTemperatures(input, current);
                }
            }
            if (advection) {
                current = engine.stepWorld(stepInput, lut, ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            }
            pendingColumnResults = current;
            return List.of();
        });
        ticksSinceSubmit = 0;
        state = State.AWAITING;
    }

    /** Build advection input columns: each column's original geometry/mass, but the post-conduction
     *  temperatures from {@code conduction} (mass carried through — conduction does not move mass). */
    private static List<ColumnTask> withTemperatures(List<ColumnTask> tasks, List<ColumnResult> conduction) {
        int n = Math.min(tasks.size(), conduction.size());
        List<ColumnTask> out = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            ColumnTask t = tasks.get(i);
            float[] postT = i < n ? conduction.get(i).temperature() : t.temperature();
            out.add(new ColumnTask(t.cx(), t.cz(), t.matIx(), t.mass(), postT));
        }
        return out;
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
        if (!ledger.conserved()) {
            LOGGER.warn("[ORGE] region step mass not conserved (per-species); holding {} columns this cycle", n);
            return;
        }
        for (int i = 0; i < n; i++) {
            world.writeBackColumn(pendingColumns.get(i), results.get(i));
        }
    }


    private void toIdle() {
        pending = null;
        pendingColumns = null;
        pendingColumnResults = null;
        ticksSinceSubmit = 0;
        state = State.IDLE;
        // tickCounter is NOT reset here: it free-runs on the global 20-tick grid so the cadences
        // stay on the same phase regardless of how long the previous step took.
    }
}
