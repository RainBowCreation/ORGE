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
            Identifier.fromNamespaceAndPath("minecraft", "water"));

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
        @Override public Batch snapshot(int range) { return batch; }
        @Override public void writeBack(BatchEntry entry, StepResult r) {
            writes.add(r.temperature());
            massWrites.add(r.mass());
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
}
