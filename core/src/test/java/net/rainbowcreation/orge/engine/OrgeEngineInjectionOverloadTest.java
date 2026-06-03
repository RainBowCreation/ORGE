package net.rainbowcreation.orge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** The default injection overload must delegate to the 4-arg stepWorld and return a zero ledger,
 *  so non-native engines (StubEngine, test fakes) keep working without implementing injections. */
class OrgeEngineInjectionOverloadTest {

    /** Minimal fake: identity step, records the columns it was handed. */
    private static final class IdentityEngine implements OrgeEngine {
        List<ColumnTask> lastColumns;
        @Override public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                                                      double dtSeconds, int passes) {
            lastColumns = columns;
            ColumnTask t = columns.get(0);
            return List.of(new ColumnResult(t.matIx().clone(), t.mass().clone(), t.temperature().clone()));
        }
        @Override public double lastStepMillis() { return 0.0; }
    }

    @Test
    void defaultOverloadDelegatesAndReturnsZeroLedger() {
        IdentityEngine engine = new IdentityEngine();
        char[] mat = new char[RegionMarshaller.CHUNK_N];
        float[] mass = new float[RegionMarshaller.CHUNK_N];
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        List<ColumnTask> cols = List.of(new ColumnTask(0, 0, mat, mass, temp));
        List<Material> lut = List.of(
                TestMaterials.voidMat(),
                TestMaterials.water());

        RegionStepResult result = engine.stepWorld(cols, lut, 0.25, OrgeEngine.PASS_ADVECTION,
                List.of(new EngineInjection(0, 42, (char) 1, 1000f, 290f)));

        assertSame(cols, engine.lastColumns);                 // delegated to the 4-arg form
        assertEquals(1, result.columns().size());
        assertEquals(lut.size(), result.injected().length);    // ledger sized to the LUT
        assertEquals(lut.size(), result.sealedLoss().length);
        for (float v : result.injected())   assertEquals(0f, v);   // default ignores injections
        for (float v : result.sealedLoss()) assertEquals(0f, v);
    }
}
