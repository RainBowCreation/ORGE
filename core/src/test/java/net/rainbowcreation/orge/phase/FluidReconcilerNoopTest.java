package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.scheduler.ThermalWorld;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidReconcilerNoopTest {
    @Test
    void noopDoesNothing() {
        ThermalWorld.BatchEntry e = new ThermalWorld.BatchEntry(
                Identifier.fromNamespaceAndPath("minecraft","overworld"),
                new SubchunkKey(0,0,0),
                new StepTask(new SubchunkKey(0,0,0), new char[4096], new float[4096], new float[4096], null));
        assertDoesNotThrow(() -> FluidReconciler.NOOP.reconcile(e));
    }
}
