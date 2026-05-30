package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import java.util.List;
import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class StubEngineMassTest {
    @Test
    void stubReturnsTemperatureAndUnchangedMass() {
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        // pass both bits; StubEngine is identity for both passes anyway.
        int both = OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION;
        List<StepResult> out = new StubEngine().step(List.of(a), stdLut(), 1.0, both);
        assertEquals(1, out.size());
        assertEquals(SectionConstants(), out.get(0).temperature().length);
        // StubEngine does not advect: mass comes back identical to the task's mass.
        assertArrayEquals(a.mass(), out.get(0).mass(), 0f);
    }
    private static int SectionConstants() { return 4096; }
}
