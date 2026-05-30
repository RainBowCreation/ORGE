package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerEngineIntegrationTest {

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    /** Inline runner: runs the step synchronously and reports done immediately. */
    private static final class InlineRunner implements StepRunner {
        @Override public Handle submit(Callable<List<StepResult>> task) {
            final List<StepResult> out;
            final RuntimeException err;
            List<StepResult> r = null; RuntimeException e = null;
            try { r = task.call(); } catch (Exception ex) { e = new RuntimeException(ex); }
            out = r; err = e;
            return new Handle() {
                @Override public boolean isDone() { return true; }
                @Override public List<StepResult> result() { if (err != null) throw err; return out; }
                @Override public void cancel() {}
            };
        }
    }

    private static final class CapturingWorld implements ThermalWorld {
        final Batch batch;
        float[] written;
        CapturingWorld(Batch b) { this.batch = b; }
        @Override public Batch snapshot(int range) { return batch; }
        @Override public void writeBack(BatchEntry entry, StepResult r) { this.written = r.temperature(); }
    }

    @Test
    void hotCellDiffusesThroughTheRealEngineAndIsWrittenBack() {
        OrgeEngine engine = EngineFactory.create();
        assumeTrue(engine instanceof NativeEngine, "native liborge not bundled; skipping");

        Material solid = new Material(Identifier.fromNamespaceAndPath("orge", "stone"),
                5.0f, 800f, 0f, 2700f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);

        MaterialLut lut = new MaterialLut();
        GeometryAssembler.Geometry geo = GeometryAssembler.assemble(i -> solid, lut);

        float[] temps = new float[SectionData.CELLS];
        Arrays.fill(temps, 300f);
        int hot = sidx(8, 8, 8);
        temps[hot] = 1000f;
        float[] before = temps.clone();

        NeighborHalo voidHalo = HaloAssembler.assemble(null, null, null, null, null, null);
        StepTask st = new StepTask(new SubchunkKey(0, 0, 0), geo.matIx(), geo.mass(), temps, voidHalo);

        ThermalWorld.Batch batch = new ThermalWorld.Batch(
                List.of(new ThermalWorld.BatchEntry(
                        Identifier.fromNamespaceAndPath("minecraft", "overworld"), st.key(), st)),
                lut.materials());

        CapturingWorld world = new CapturingWorld(batch);
        Worker worker = new Worker(UUID.randomUUID(), true, 2, 4, 1000.0, 5);
        Scheduler s = new Scheduler(engine, world, new InlineRunner(), worker);

        for (int i = 0; i < Scheduler.TICKS_PER_STEP; i++) s.onServerTick(); // submit
        s.onServerTick(); // inline step already done -> write back

        assertNotNull(world.written, "a result was written back");
        assertTrue(world.written[hot] < before[hot], "hot cell cooled");
        int neighbour = sidx(9, 8, 8);
        assertTrue(world.written[neighbour] > before[neighbour], "adjacent cell warmed");
        for (float v : world.written) {
            assertTrue(Float.isFinite(v) && v >= 0f && v <= 6000f, "validated range");
        }
    }
}
