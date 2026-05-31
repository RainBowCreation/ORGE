package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the active-set gating against a fake world whose {@code snapshot} consults a real
 * {@link ActiveSet}. After K advection + K conduction quiet steps the section must drop out of the
 * snapshot; a wake re-admits it. Isolates the §10 Decision 11 dormancy gating from Minecraft.
 */
class SchedulerDormancyTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final SubchunkKey K = new SubchunkKey(0, 0, 0);

    /** Minimal {@link ThermalWorld} whose snapshot returns the section only when the active set admits it. */
    private static final class ActiveSetWorld implements ThermalWorld {
        private final ActiveSet active;
        private final Identifier dim;
        private final SubchunkKey key;

        ActiveSetWorld(ActiveSet active, Identifier dim, SubchunkKey key) {
            this.active = active;
            this.dim = dim;
            this.key = key;
        }

        @Override
        public Batch snapshot(int range) {
            List<BatchEntry> entries = new ArrayList<>();
            for (SubchunkKey k : active.activeWithin(dim, List.of(key))) {
                char[] matIx = new char[16 * 16 * 16];
                float[] mass = new float[matIx.length];
                float[] temps = new float[matIx.length];
                entries.add(new BatchEntry(dim, k, new StepTask(k, matIx, mass, temps, null)));
            }
            return new Batch(entries, List.of());
        }

        @Override
        public void writeBack(BatchEntry entry, StepResult result) {
            // no-op for this gating test
        }
    }

    @Test
    void settledSectionStopsBeingSnapshotted_thenWakeReadmitsIt() {
        ActiveSet active = new ActiveSet();
        ActiveSetWorld world = new ActiveSetWorld(active, DIM, K);
        // Bring the section into tracking, then feed K quiet flow + K quiet thermal steps so it sleeps.
        active.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) {
            active.noteFlowDelta(DIM, K, 0f);
            active.noteThermalDelta(DIM, K, 0f);
        }
        assertTrue(active.isAsleep(DIM, K));
        assertTrue(world.snapshot(2).entries().isEmpty(), "asleep section is not snapshotted");

        active.wakeBlock(DIM, 0, 0, 0); // a bucket placement in that section
        assertFalse(world.snapshot(2).entries().isEmpty(), "wake re-admits the section");
    }
}
