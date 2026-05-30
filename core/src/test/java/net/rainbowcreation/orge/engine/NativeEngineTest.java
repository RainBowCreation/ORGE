package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeEngineTest {

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    @Test
    void uniformFieldIsUnchanged() {
        NativeEngine e = engineOrSkip();
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        List<StepResult> out = e.step(List.of(a), stdLut(), 1.0, OrgeEngine.PASS_CONDUCTION);
        assertEquals(1, out.size());
        for (float v : out.get(0).temperature()) assertEquals(300f, v, 1e-4f);
    }

    @Test
    void heatFlowsHotToColdWithinSection() {
        NativeEngine e = engineOrSkip();
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        int hot = 8 + 16 * 8 + 256 * 8;          // sidx(8,8,8)
        int nb = 9 + 16 * 8 + 256 * 8;           // sidx(9,8,8)
        a.temperature()[hot] = 1000f;
        List<StepResult> out = e.step(List.of(a), stdLut(), 1.0, OrgeEngine.PASS_CONDUCTION);
        assertTrue(out.get(0).temperature()[hot] < 1000f, "hot cell cools");
        assertTrue(out.get(0).temperature()[nb] > 300f, "neighbour warms");
        assertTrue(e.lastStepMillis() >= 0.0);
    }

    @Test
    void batchPreservesOrder() {
        NativeEngine e = engineOrSkip();
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        StepTask b = solidSection(new SubchunkKey(1, 0, 0), 500f);
        List<StepResult> out = e.step(List.of(a, b), stdLut(), 1.0, OrgeEngine.PASS_CONDUCTION);
        assertEquals(2, out.size());
        assertEquals(300f, out.get(0).temperature()[0], 1e-4f);
        assertEquals(500f, out.get(1).temperature()[0], 1e-4f);
    }

    @Test
    void matchesStubOnUniformField() {
        NativeEngine e = engineOrSkip();
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 285f);
        List<StepResult> nativeOut = e.step(List.of(a), stdLut(), 1.0, OrgeEngine.PASS_CONDUCTION);
        List<StepResult> stubOut = new StubEngine().step(List.of(a), stdLut(), 1.0, OrgeEngine.PASS_CONDUCTION);
        assertArrayEquals(stubOut.get(0).temperature(), nativeOut.get(0).temperature(), 1e-4f);
    }

    @Test
    void emptyBatchReturnsEmpty() {
        NativeEngine e = engineOrSkip();
        assertTrue(e.step(List.of(), stdLut(), 1.0, OrgeEngine.PASS_CONDUCTION).isEmpty());
    }

    @Test
    void nativeAdvectsMassDownward() {
        NativeEngine e = engineOrSkip();
        // a fluid section: build via BatchTestSupport.fluidSection with the top
        // plane full and the cell below empty; after one step the lower cell gains mass.
        StepTask a = fluidSection(new SubchunkKey(0, 0, 0));
        int both = OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION;
        List<StepResult> out = e.step(List.of(a), fluidLut(), 0.25, both); // advection dt
        int top = 0 + 16 * 1 + 256 * 0; // sidx(0,1,0)
        int bot = 0 + 16 * 0 + 256 * 0; // sidx(0,0,0)
        assertTrue(out.get(0).mass()[bot] > out.get(0).mass()[top], "mass fell downward");
        float total = out.get(0).mass()[top] + out.get(0).mass()[bot];
        assertEquals(1000f, total, 1f, "mass conserved in the falling pair");
    }
}
