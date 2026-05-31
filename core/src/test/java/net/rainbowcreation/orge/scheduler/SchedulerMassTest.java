package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.engine.StubEngine;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 17: the two decoupled cadences. Advection fires every 5 ticks (ticks 5,10,15,20),
 * conduction once every 20 (tick 20); on the coincident tick conduction runs before advection.
 * Advection writes mass back only when Σmass is conserved (else holds previous), runs the
 * {@link net.rainbowcreation.orge.phase.FluidReconciler} once per written section, and does not
 * perturb the temperature field the conduction path produced.
 */
class SchedulerMassTest {

    /** A fluid material with non-zero defaultMass so the batch's fullMassBound > 0. */
    private static final Material WATER = new Material(
            Identifier.fromNamespaceAndPath("minecraft", "water"),
            0.6f, 4186f, 1f, 1000f, 18f,
            373.15f, 273.15f, null, null,
            Identifier.fromNamespaceAndPath("minecraft", "water"),
            Float.NaN, false, true); // fluid=true: the §9 mass gate only validates fluid cells

    /**
     * Synchronous runner mirroring the production background runner: the job is executed
     * eagerly at {@code submit} (so {@code engine.step} runs on the submit tick, as it would
     * on a real worker thread) and the result is cached for {@code result()}; the handle is
     * immediately "done".
     */
    private static final class FakeRunner implements StepRunner {
        List<StepResult> cached;
        RuntimeException failure;
        @Override public Handle submit(Callable<List<StepResult>> t) {
            this.failure = null;
            try {
                this.cached = t.call();
            } catch (RuntimeException e) {
                this.failure = e;
            } catch (Exception e) {
                this.failure = new RuntimeException(e);
            }
            return new Handle() {
                @Override public boolean isDone() { return true; }
                @Override public List<StepResult> result() {
                    if (failure != null) throw failure;
                    return cached;
                }
                @Override public void cancel() {}
            };
        }
    }

    private static final class CountingReconciler
            implements net.rainbowcreation.orge.phase.FluidReconciler {
        int count;
        final List<SubchunkKey> reconciled = new ArrayList<>();
        @Override public void reconcile(ThermalWorld.BatchEntry entry) {
            count++;
            reconciled.add(entry.key());
        }
    }

    private static final class FakeWorld implements ThermalWorld {
        Batch batch;
        final List<float[]> writes = new ArrayList<>();
        final List<float[]> massWrites = new ArrayList<>();
        int recordCount;
        final List<char[]> recordedOutMat = new ArrayList<>();
        int settleCount;
        int wakeCount;
        @Override public Batch snapshot(int range) { return batch; }
        @Override public void writeBack(BatchEntry entry, StepResult r) {
            writes.add(r.temperature());
            massWrites.add(r.mass());
        }
        @Override public void recordCellMaterials(BatchEntry entry, char[] outMat, List<Material> lut) {
            recordCount++;
            recordedOutMat.add(outMat);
        }
        @Override public void noteSettle(BatchEntry entry, float maxMassDelta, float maxTempDelta) {
            settleCount++;
        }
        @Override public void wakeNeighbourFlow(net.minecraft.resources.Identifier dim,
                net.rainbowcreation.orge.section.SubchunkKey neighbour) {
            wakeCount++;
        }
    }

    /** A recording engine: captures the {@code passes} of every step call, returns identity. */
    private static final class RecordingEngine implements OrgeEngine {
        final List<Integer> passesSeen = new ArrayList<>();
        @Override public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
            passesSeen.add(passes);
            List<StepResult> out = new ArrayList<>(tasks.size());
            for (StepTask t : tasks) out.add(new StepResult(t.temperature().clone(), t.mass().clone()));
            return out;
        }
        @Override public double lastStepMillis() { return 1.0; }
    }

    private static StepTask fluidTask(float temp) {
        float[] t = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, temp);
        char[] m = new char[SectionData.CELLS]; // all material index 1 (water in our lut)
        java.util.Arrays.fill(m, (char) 1);
        float[] mass = new float[SectionData.CELLS]; // empty everywhere...
        mass[0] = 1000f;                            // ...except the top cell is full.
        NeighborHalo halo = HaloAssembler.assemble(null, null, null, null, null, null);
        return new StepTask(new SubchunkKey(0, 0, 0), m, mass, t, halo);
    }

    private static ThermalWorld.Batch fluidBatch(float temp) {
        StepTask st = fluidTask(temp);
        return new ThermalWorld.Batch(
                List.of(new ThermalWorld.BatchEntry(
                        Identifier.fromNamespaceAndPath("minecraft", "overworld"), st.key(), st)),
                List.of(MaterialLut.VOID, WATER));
    }

    private static Worker worker() {
        return new Worker(UUID.randomUUID(), true, 2, 4, 250.0, 3);
    }

    @Test
    void advectionCadenceFiresEvery5TicksConductionEvery20() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = fluidBatch(290f);
        RecordingEngine engine = new RecordingEngine();
        CountingReconciler reconciler = new CountingReconciler();
        Scheduler s = new Scheduler(engine, world, runner, worker(),
                net.rainbowcreation.orge.phase.PhaseChanger.NOOP, reconciler);

        for (int i = 0; i < 20; i++) s.onServerTick();

        int advectionSteps = 0;
        int conductionSteps = 0;
        for (int p : engine.passesSeen) {
            if ((p & OrgeEngine.PASS_ADVECTION) != 0) advectionSteps++;
            if ((p & OrgeEngine.PASS_CONDUCTION) != 0) conductionSteps++;
        }
        assertEquals(4, advectionSteps, "advection fired on ticks 5,10,15,20");
        assertEquals(1, conductionSteps, "conduction fired once, on tick 20");

        // Coincident order: the conduction call precedes the advection call on tick 20. The first
        // three calls are the advection-only boundaries; the 4th call is the coincident conduction,
        // the 5th is the coincident advection (conduction submitted/executed before advection).
        assertEquals(5, engine.passesSeen.size(), "3 advection-only + 1 conduction + 1 advection");
        assertEquals(OrgeEngine.PASS_CONDUCTION, (int) engine.passesSeen.get(3),
                "tick 20: conduction runs first");
        assertEquals(OrgeEngine.PASS_ADVECTION, (int) engine.passesSeen.get(4),
                "tick 20: advection runs after conduction");
    }

    @Test
    void advectionConservesMassWithoutPerturbingConductionPath() {
        // --- Conduction-only baseline: capture the temperatures the conduction path writes. ---
        FakeRunner condRunner = new FakeRunner();
        FakeWorld condWorld = new FakeWorld();
        condWorld.batch = fluidBatch(290f);
        Scheduler condS = new Scheduler(new StubEngine(), condWorld, condRunner, worker());
        for (int i = 0; i < 20; i++) condS.onServerTick(); // reach + run the coincident tick 20
        assertFalse(condWorld.writes.isEmpty(), "the conduction boundary wrote temperatures");
        float[] conductionT = condWorld.writes.get(condWorld.writes.size() - 1);

        // --- Advection-only step (StubEngine identity): mass conserved, T unchanged. ---
        FakeRunner advRunner = new FakeRunner();
        FakeWorld advWorld = new FakeWorld();
        advWorld.batch = fluidBatch(290f);
        CountingReconciler reconciler = new CountingReconciler();
        Scheduler advS = new Scheduler(new StubEngine(), advWorld, advRunner, worker(),
                net.rainbowcreation.orge.phase.PhaseChanger.NOOP, reconciler);
        // tick 5 submits the advection-only step (eager engine run); tick 6 services + writes it back.
        for (int i = 0; i < 6; i++) advS.onServerTick();

        assertEquals(1, advWorld.writes.size(), "advection-only step wrote back once");
        // Σmass unchanged (identity engine): top cell 1000, rest 0 -> total 1000.
        double sum = 0;
        for (float v : advWorld.massWrites.get(0)) sum += v;
        assertEquals(1000.0, sum, 1e-3, "advection conserved total mass");
        // Temperature field equals the conduction-only result (advection alone did not change T).
        assertArrayEquals(conductionT, advWorld.writes.get(0), 0f,
                "advection did not perturb the conduction temperature field");
        assertEquals(1, reconciler.count, "reconciler fired once per written section per advection cadence");
        assertEquals(new SubchunkKey(0, 0, 0), reconciler.reconciled.get(0));

        // --- Non-conserving advection result is rejected (previous mass held -> no write-back). ---
        FakeRunner badRunner = new FakeRunner();
        FakeWorld badWorld = new FakeWorld();
        badWorld.batch = fluidBatch(290f);
        CountingReconciler badReconciler = new CountingReconciler();
        OrgeEngine fabricatingEngine = new OrgeEngine() {
            @Override public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
                List<StepResult> out = new ArrayList<>(tasks.size());
                for (StepTask t : tasks) {
                    float[] mass = t.mass().clone();
                    mass[1] += 500f; // fabricate mass out of nowhere -> violates conservation
                    out.add(new StepResult(t.temperature().clone(), mass));
                }
                return out;
            }
            @Override public double lastStepMillis() { return 1.0; }
        };
        Scheduler badS = new Scheduler(fabricatingEngine, badWorld, badRunner, worker(),
                net.rainbowcreation.orge.phase.PhaseChanger.NOOP, badReconciler);
        for (int i = 0; i < 6; i++) badS.onServerTick();
        assertEquals(0, badWorld.writes.size(), "non-conserving advection result rejected (held previous)");
        assertEquals(0, badReconciler.count, "no reconcile for a held (rejected) section");
    }

    /** An engine that conserves mass and emits an output species array as a DISTINCT instance
     *  (a fresh clone, NOT the task's matIx) so the scheduler test can prove by reference identity
     *  that recordCellMaterials received result.material() and not entry.task().matIx(). */
    private static final class SpeciesEngine implements OrgeEngine {
        /** The exact material() instance the last step returned, for assertSame in the test. */
        char[] lastOutMat;
        @Override public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
            List<StepResult> out = new ArrayList<>(tasks.size());
            for (StepTask t : tasks) {
                char[] outMat = t.matIx().clone(); // same species values, but a DISTINCT instance
                lastOutMat = outMat;
                out.add(new StepResult(t.temperature().clone(), t.mass().clone(),
                        outMat)); // conserved, non-null material(), distinct from task.matIx()
            }
            return out;
        }
        @Override public double lastStepMillis() { return 1.0; }
    }

    @Test
    void conservedAdvectionRecordsCellMaterialsWithEngineOutput() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = fluidBatch(290f);
        SpeciesEngine engine = new SpeciesEngine();
        Scheduler s = new Scheduler(engine, world, runner, worker(),
                net.rainbowcreation.orge.phase.PhaseChanger.NOOP, new CountingReconciler());
        // tick 5 submits the advection-only step; tick 6 services + writes it back.
        for (int i = 0; i < 6; i++) s.onServerTick();

        assertEquals(1, world.writes.size(), "advection-only step wrote back once");
        assertEquals(1, world.recordCount, "recordCellMaterials called once for the written section");
        char[] recorded = world.recordedOutMat.get(0);
        assertNotNull(recorded, "engine output species forwarded to recordCellMaterials");
        // Reference identity proves the scheduler forwarded r.material() (the DISTINCT instance the
        // engine returned), NOT entry.task().matIx(). SpeciesEngine returns a fresh clone holding the
        // same species values, so an assertEquals on contents could not tell the two arrays apart —
        // only assertSame pins that the exact material() array was forwarded.
        assertSame(engine.lastOutMat, recorded,
                "recordCellMaterials got the exact result.material() instance, not task.matIx()");
    }

    /** A two-section fluid batch: each section is a full top cell (1000 kg) over empty cells. */
    private static ThermalWorld.Batch twoSectionBatch(float temp) {
        StepTask a = fluidTaskAt(new SubchunkKey(0, 0, 0), temp);
        StepTask b = fluidTaskAt(new SubchunkKey(0, 1, 0), temp);
        Identifier dim = Identifier.fromNamespaceAndPath("minecraft", "overworld");
        return new ThermalWorld.Batch(
                List.of(new ThermalWorld.BatchEntry(dim, a.key(), a),
                        new ThermalWorld.BatchEntry(dim, b.key(), b)),
                List.of(MaterialLut.VOID, WATER));
    }

    private static StepTask fluidTaskAt(SubchunkKey key, float temp) {
        float[] t = new float[SectionData.CELLS]; java.util.Arrays.fill(t, temp);
        char[] m = new char[SectionData.CELLS]; java.util.Arrays.fill(m, (char) 1);
        float[] mass = new float[SectionData.CELLS]; mass[0] = 1000f;
        NeighborHalo halo = HaloAssembler.assemble(null, null, null, null, null, null);
        return new StepTask(key, m, mass, t, halo);
    }

    @Test
    void crossSeamBatchUnbalancedPerSectionButBalancedAsBatchIsWrittenBack() {
        // Section A's full cell falls ACROSS the seam into section B: A loses 1000 kg, B gains 1000 kg.
        // Per section neither conserves (A short 1000, B long 1000), but the BATCH sum cancels, so the
        // validate-then-write batch gate must accept BOTH sections and write both back.
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = twoSectionBatch(290f);
        CountingReconciler reconciler = new CountingReconciler();
        // NB: section B putting 2000 kg in one cell would trip the per-cell BOUND (cap 1000). Land the
        // gained mass in a SECOND (empty) cell so the bound passes and only the per-section CONSERVATION
        // is what would have rejected — which is exactly what the batch ledger must rescue.
        OrgeEngine engine = new OrgeEngine() {
            @Override public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
                List<StepResult> out = new ArrayList<>(tasks.size());
                for (int i = 0; i < tasks.size(); i++) {
                    StepTask t = tasks.get(i);
                    float[] mass = t.mass().clone();
                    if (i == 0) {
                        mass[0] = 0f;               // A loses its 1000 kg across the seam
                    } else {
                        mass[1] = 1000f;            // B gains 1000 kg in an adjacent (empty) cell
                    }
                    out.add(new StepResult(t.temperature().clone(), mass, t.matIx().clone()));
                }
                return out;
            }
            @Override public double lastStepMillis() { return 1.0; }
        };
        Scheduler s = new Scheduler(engine, world, runner, worker(),
                net.rainbowcreation.orge.phase.PhaseChanger.NOOP, reconciler);
        for (int i = 0; i < 6; i++) s.onServerTick();

        assertEquals(2, world.writes.size(), "both sections written back (batch conserved)");
        assertEquals(2, reconciler.count, "both sections reconciled");
        // A's total mass dropped to 0, B's rose to 2000 (1000 original + 1000 gained).
        double sumA = 0, sumB = 0;
        for (float v : world.massWrites.get(0)) sumA += v;
        for (float v : world.massWrites.get(1)) sumB += v;
        assertEquals(0.0, sumA, 1e-3, "section A drained across the seam");
        assertEquals(2000.0, sumB, 1e-3, "section B received the cross-seam mass");
    }

    @Test
    void batchWithRealFabricationHoldsAllSections() {
        // Section A conserves perfectly; section B fabricates 800 kg with no donor. The batch sum is
        // long by 800 kg -> the whole batch is held: NEITHER section's mass is written back.
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = twoSectionBatch(290f);
        CountingReconciler reconciler = new CountingReconciler();
        OrgeEngine engine = new OrgeEngine() {
            @Override public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
                List<StepResult> out = new ArrayList<>(tasks.size());
                for (int i = 0; i < tasks.size(); i++) {
                    StepTask t = tasks.get(i);
                    float[] mass = t.mass().clone();
                    if (i == 1) mass[1] = 800f;     // B invents 800 kg (no donor) in an empty cell
                    out.add(new StepResult(t.temperature().clone(), mass, t.matIx().clone()));
                }
                return out;
            }
            @Override public double lastStepMillis() { return 1.0; }
        };
        Scheduler s = new Scheduler(engine, world, runner, worker(),
                net.rainbowcreation.orge.phase.PhaseChanger.NOOP, reconciler);
        for (int i = 0; i < 6; i++) s.onServerTick();

        assertEquals(0, world.writes.size(), "fabricating batch held: no section written back");
        assertEquals(0, reconciler.count, "no reconcile for a held batch");
        assertEquals(0, world.recordCount, "no cell-material record for a held batch");
        assertEquals(0, world.settleCount, "no noteSettle side effect for a held batch");
        assertEquals(0, world.wakeCount, "no wakeNeighbourFlow side effect for a held batch");
    }

    @Test
    void heldAdvectionDoesNotRecordCellMaterials() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = fluidBatch(290f);
        // Non-conserving engine: fabricates mass -> §9 holds the section (no write-back).
        OrgeEngine fabricatingEngine = new OrgeEngine() {
            @Override public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
                List<StepResult> out = new ArrayList<>(tasks.size());
                for (StepTask t : tasks) {
                    float[] mass = t.mass().clone();
                    mass[1] += 500f; // fabricate mass -> violates conservation
                    out.add(new StepResult(t.temperature().clone(), mass, t.matIx().clone()));
                }
                return out;
            }
            @Override public double lastStepMillis() { return 1.0; }
        };
        Scheduler s = new Scheduler(fabricatingEngine, world, runner, worker(),
                net.rainbowcreation.orge.phase.PhaseChanger.NOOP, new CountingReconciler());
        for (int i = 0; i < 6; i++) s.onServerTick();

        assertEquals(0, world.writes.size(), "held section is not written back");
        assertEquals(0, world.recordCount, "held (rejected) section does not record cell materials");
    }
}
