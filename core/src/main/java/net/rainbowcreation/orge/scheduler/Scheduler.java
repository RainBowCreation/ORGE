package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private List<ThermalWorld.BatchEntry> pendingEntries;

    /** Which passes the in-flight job ran (captured at submit), to pick the writeback set. */
    private boolean pendingConduction;
    private boolean pendingAdvection;
    /** Largest legal per-cell mass for this batch (max material defaultMass), for the §9 check. */
    private float pendingFullMassBound;
    /** Batch material table (captured at submit), so the §9 gate can exempt non-fluid cells. */
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
        // Measure the server-thread cost of assembling the snapshot (geometry + halos): this is
        // a dominant per-cycle cost ON the server thread and must feed the health throttle.
        long snapStart = System.nanoTime();
        ThermalWorld.Batch batch = world.snapshot(worker.range());
        pendingSnapshotNanos = System.nanoTime() - snapStart;
        if (batch.entries().isEmpty()) {
            return; // stay IDLE; nothing to simulate this step
        }
        List<StepTask> tasks = batch.entries().stream().map(ThermalWorld.BatchEntry::task).toList();
        List<Material> lut = batch.lut();
        // fullMassBound = max material defaultMass over the batch LUT (a constant per step).
        float fullMassBound = 0f;
        for (Material m : lut) {
            if (m.defaultMass() > fullMassBound) fullMassBound = m.defaultMass();
        }
        pendingFullMassBound = fullMassBound;
        pendingMaterials = lut;
        pendingConduction = conduction;
        pendingAdvection = advection;
        pending = runner.submit(() -> {
            List<StepResult> current = null;
            List<StepTask> stepInput = tasks;
            if (conduction) {
                current = engine.step(stepInput, lut, STEP_DT_SECONDS, OrgeEngine.PASS_CONDUCTION);
                if (advection) {
                    // Advection runs on the post-conduction temperature field (mass carried
                    // through from the original snapshot — conduction does not move mass).
                    stepInput = withTemperatures(tasks, current);
                }
            }
            if (advection) {
                current = engine.step(stepInput, lut, ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            }
            return current;
        });
        pendingEntries = batch.entries();
        ticksSinceSubmit = 0;
        state = State.AWAITING;
    }

    /** Build advection input tasks: each section's original geometry/mass/halo, but the
     *  post-conduction temperatures from {@code conduction}. */
    private static List<StepTask> withTemperatures(List<StepTask> tasks, List<StepResult> conduction) {
        int n = Math.min(tasks.size(), conduction.size());
        StepTask[] out = new StepTask[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            StepTask t = tasks.get(i);
            float[] postT = i < n ? conduction.get(i).temperature() : t.temperature();
            out[i] = new StepTask(t.key(), t.matIx(), t.mass(), postT, t.halo());
        }
        return List.of(out);
    }

    private void complete(boolean metDeadline) {
        // Time the server-thread phase of this cycle (write-back + §9 + phase/reconcile). Summed
        // with the snapshot time measured in submit() to give the per-cycle server-thread cost,
        // which feeds the health throttle (NOT engine.lastStepMillis(), the off-thread native step).
        long completeStart = System.nanoTime();
        List<StepResult> results;
        try {
            results = pending.result();
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
            writeBackResults(results, metDeadline);
        } finally {
            double serverThreadMillis = (pendingSnapshotNanos + (System.nanoTime() - completeStart)) / 1_000_000.0;
            // engine.lastStepMillis() is the off-thread native step; logged for audit only — it
            // does NOT drive the throttle (that would be blind to the dominant server-thread cost).
            worker.noteStep(serverThreadMillis, metDeadline);
            toIdle();
        }
    }

    /** Write each result back per cadence (§9 validate, writeBack, phase/reconcile). Runs on the
     *  server thread; its wall-time is part of the per-cycle budget the throttle measures. */
    private void writeBackResults(List<StepResult> results, boolean metDeadline) {
        if (results.size() < pendingEntries.size()) {
            LOGGER.warn("[ORGE] engine returned {} results for {} sections; trailing sections hold previous values",
                    results.size(), pendingEntries.size());
        }
        boolean conduction = pendingConduction;
        boolean advection = pendingAdvection;
        float fullMassBound = pendingFullMassBound;
        int n = Math.min(results.size(), pendingEntries.size());
        for (int i = 0; i < n; i++) {
            ThermalWorld.BatchEntry entry = pendingEntries.get(i);
            StepResult r = results.get(i);
            float[] cleanT = StepValidator.clean(r.temperature(), entry.task().temperature());

            if (advection) {
                // Advection cycle (and the advection half of a coincident tick): mass moved, so
                // validate Σmass and write T + mass; a non-conserving result holds previous mass.
                float[] cleanM = StepValidator.cleanMass(r.mass(), fullMassBound);
                char[] outMat = r.material();
                if (outMat != null) {
                    // Preserve any over-cap parcel (a §7/engine boil deposit) that cleanMass would
                    // otherwise clamp to fullMassBound and destroy (Decision 12 landmine). Key on the
                    // cell being over its OWN output-species cap — robust across the multi-step relaxation,
                    // not just the flip step (a boiled steam cell stays steam while it relaxes).
                    for (int c = 0; c < cleanM.length; c++) {
                        int s = outMat[c];
                        if (s != 0 && pendingMaterials.get(s).fluid()
                                && Float.isFinite(r.mass()[c]) && r.mass()[c] >= 0f
                                && r.mass()[c] > pendingMaterials.get(s).maxMass()) {
                            cleanM[c] = r.mass()[c]; // boil-volume: keep the over-cap deposit unclamped
                        }
                    }
                    if (!StepValidator.massConservedPerSpecies(cleanM, entry.task().mass(),
                            entry.task().matIx(), outMat, pendingMaterials)) {
                        LOGGER.warn("[ORGE] advection mass not conserved (per-species) for {}; holding previous mass",
                                entry.key());
                        // Hold previous: skip write-back (and reconcile) for this entry. On a
                        // coincident tick conduction's heat is folded into the advection T, so we do
                        // NOT separately write T here — holding the whole entry is the safe choice.
                        continue;
                    }
                } else {
                    // No engine species (stub/back-compat): fall back to the total-fluid gate.
                    if (!StepValidator.massConserved(cleanM, entry.task().mass(), fullMassBound,
                            entry.task().matIx(), pendingMaterials)) {
                        LOGGER.warn("[ORGE] advection mass not conserved for {}; holding previous mass",
                                entry.key());
                        continue;
                    }
                }
                world.writeBack(entry, new StepResult(cleanT, cleanM, r.material()));
                // §10 follow-on (reseed-misfire fix): record the engine's OUTPUT species as the
                // signature for this section's just-persisted mass. The NEXT snapshot's
                // MaterialChangeReseed then sees the reconciler's matching fluid placement as
                // already-known (no reseed → conservation) and reseeds only genuine external edits.
                // Runs only on the conserved path — a held (§9-rejected) section `continue`d above.
                world.recordCellMaterials(entry, r.material(), pendingMaterials);
                // §10 Decision 11: piggyback the settle reduction on this loop (near-free, one
                // max-reduction per array). On a coincident tick conduction's ΔT is already folded
                // into cleanT, so report both deltas; otherwise the flow delta only.
                float maxMassDelta = maxAbsDelta(cleanM, entry.task().mass());
                float maxTempDelta = conduction ? maxAbsDelta(cleanT, entry.task().temperature()) : -1f;
                world.noteSettle(entry, maxMassDelta, maxTempDelta);
                // §10 Decision 11 trigger (c): if mass crossed any of the six boundary faces this step,
                // wake the adjacent section's flow pass so flow propagates into a dormant border instead
                // of stopping dead. Reads only the six 16×16 boundary planes (cheap, no extra grid pass)
                // off the data already in hand (snapshot mass vs post-step mass). A held entry never
                // reaches here (the §9 gate `continue`d above), so it correctly wakes no neighbour.
                float[] inMass = entry.task().mass();
                boolean negX = faceMoved(inMass, cleanM, Face.NEG_X);
                boolean posX = faceMoved(inMass, cleanM, Face.POS_X);
                boolean negY = faceMoved(inMass, cleanM, Face.NEG_Y);
                boolean posY = faceMoved(inMass, cleanM, Face.POS_Y);
                boolean negZ = faceMoved(inMass, cleanM, Face.NEG_Z);
                boolean posZ = faceMoved(inMass, cleanM, Face.POS_Z);
                if (negX || posX || negY || posY || negZ || posZ) {
                    for (SubchunkKey nb : SeamFluxWake.neighboursToWake(entry.key(),
                            negX, posX, negY, posY, negZ, posZ)) {
                        world.wakeNeighbourFlow(entry.dimension(), nb);
                    }
                }
                if (conduction) {
                    // Coincident tick: conduction's within-cell exchange is already reflected in
                    // cleanT (advection stepped the post-conduction field), so phase change runs
                    // on the freshly written section.
                    phaseChanger.applyPhaseChanges(entry);
                }
                fluidReconciler.reconcile(entry, r.material(), pendingMaterials);
            } else {
                // Conduction-only cycle: mass does not move, carry the snapshot mass through.
                world.writeBack(entry, new StepResult(cleanT, entry.task().mass()));
                // Keep the material signature fresh even on a hypothetical conduction-only cycle (none are
                // submitted today, but the advection cadence is audit-tunable): conduction changes no species,
                // so the input/world materials are authoritative. Passing null outMat falls back to them.
                world.recordCellMaterials(entry, null, pendingMaterials);
                world.noteSettle(entry, -1f, maxAbsDelta(cleanT, entry.task().temperature()));
                phaseChanger.applyPhaseChanges(entry);
            }
        }

        // §10 cross-section fall (Task 3 wiring): only on an advection cycle, settle fluid that
        // piled on each section's floor across the Y seam into the section below, then re-render
        // every section the seam pass mutated. The donor may be reconciled twice this cycle (full
        // in the loop, drained here — idempotent); the RECEIVER, which may not be a batch entry at
        // all, is rendered ONLY here. Reads the §5 store, not `results`, so a partial result set is
        // fine. Never runs on a conduction-only cycle (mass does not move).
        if (advection) {
            List<ThermalWorld.TouchedSection> touched =
                    world.settleCrossSectionSeams(pendingEntries, pendingMaterials);
            List<ThermalWorld.TouchedSection> safeList = touched != null ? touched : List.of();
            for (ThermalWorld.TouchedSection t : safeList) {
                fluidReconciler.reconcile(t.dim(), t.key(), t.species(), pendingMaterials);
            }
        }
    }

    /** Max absolute per-cell difference of two equal-length arrays (the settle reduction). */
    private static float maxAbsDelta(float[] a, float[] b) {
        float m = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            float d = Math.abs(a[i] - b[i]);
            if (d > m) m = d;
        }
        return m;
    }

    private enum Face { NEG_X, POS_X, NEG_Y, POS_Y, NEG_Z, POS_Z }

    /** Cell layout matches the kernel: i = x + y*16 + z*256, SEC = 16. */
    private static final int SEC = 16;

    /** True if any cell on the given boundary plane changed mass beyond the settle epsilon. The
     *  six planes are 16×16 cells each — this never touches the section's interior 4096 cells. */
    private static boolean faceMoved(float[] before, float[] after, Face face) {
        for (int a = 0; a < SEC; a++) {
            for (int b = 0; b < SEC; b++) {
                int i = faceIndex(face, a, b);
                if (Math.abs(after[i] - before[i]) >= SettleCountdown.EPS_MASS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int faceIndex(Face face, int a, int b) {
        return switch (face) {
            case NEG_X -> idx(0, a, b);
            case POS_X -> idx(SEC - 1, a, b);
            case NEG_Y -> idx(a, 0, b);
            case POS_Y -> idx(a, SEC - 1, b);
            case NEG_Z -> idx(a, b, 0);
            case POS_Z -> idx(a, b, SEC - 1);
        };
    }

    private static int idx(int x, int y, int z) {
        return x + y * SEC + z * SEC * SEC;
    }

    private void toIdle() {
        pending = null;
        pendingEntries = null;
        ticksSinceSubmit = 0;
        state = State.IDLE;
        // tickCounter is NOT reset here: it free-runs on the global 20-tick grid so the cadences
        // stay on the same phase regardless of how long the previous step took.
    }
}
