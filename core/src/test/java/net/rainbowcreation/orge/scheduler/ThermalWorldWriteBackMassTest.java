package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThermalWorldWriteBackMassTest {
    @Test
    void writeBackReceivesTemperatureAndMass() {
        float[] t = new float[4096]; float[] m = new float[4096];
        t[0] = 350f; m[0] = 640f;
        StepResult[] seen = new StepResult[1];
        ThermalWorld w = new ThermalWorld() {
            public Batch snapshot(int range) { return new Batch(java.util.List.of(), java.util.List.of()); }
            public void writeBack(BatchEntry e, StepResult r) { seen[0] = r; }
        };
        ThermalWorld.BatchEntry e = new ThermalWorld.BatchEntry(
                Identifier.fromNamespaceAndPath("minecraft","overworld"),
                new SubchunkKey(0,0,0),
                new StepTask(new SubchunkKey(0,0,0), new char[4096], new float[4096], new float[4096], null));
        w.writeBack(e, new StepResult(t, m));
        assertEquals(350f, seen[0].temperature()[0], 0f);
        assertEquals(640f, seen[0].mass()[0], 0f);
    }
}
