package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #31 — a native step must not freeze the JVM.
 *
 * <p>On a live server the engine runs on its own compute thread while the server thread keeps ticking
 * (and keeps every connected client's keep-alive). {@code orgeStepWorld} used to hold every Java array in a
 * JNI critical region ({@code GetPrimitiveArrayCritical}) for the WHOLE step, which blocks the garbage
 * collector: the first allocation on any thread that needs a GC waits until the step ends. A step over a
 * few dozen columns takes seconds to minutes, so the server froze, and a vanilla client timed out
 * ("client timed out after 30000 milliseconds"). The arrays now cross the seam by copy, and no critical
 * region is open while the engine runs.</p>
 *
 * <p>Scene: water and air columns (real flow, so the step does work), stepped by ONE call on a background
 * thread. While it runs, the test thread allocates a whole heap's worth of short-lived garbage (as a ticking
 * server thread does); that needs garbage collections, and must finish while the call is still in
 * flight.</p>
 */
@Tag("integration")
class JniStepGcIT {

    private static final int N = RegionMarshaller.CHUNK_N;
    private static final char L_VOID = 0, L_STONE = 1, L_AIR = 2, L_WATER = 3;
    private static final List<Material> LUT =
            List.of(TestMaterials.voidMat(), TestMaterials.stone(), TestMaterials.air(), TestMaterials.water());
    private static final int EPOCH = 3101;
    private static final int PASSES = 0b11;
    private static final double DT = 0.5;

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    /** A column with a stone floor, a dam-break of water on one half, air above. */
    private static ColumnTask column(int cx, int cz) {
        char[] mat = new char[N];
        float[] mass = new float[N], t = new float[N];
        double[] e = new double[N];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 140; y++) {
            int i = RegionMarshaller.colIdx(x, y, z);
            char m = y < 60 ? L_STONE : (x < 8 && y < 120) ? L_WATER : L_AIR;
            mat[i] = m;
            mass[i] = m == L_STONE ? 2700f : m == L_WATER ? 1000f : 1.2f;
            t[i] = 290f;
            e[i] = (double) mass[i] * (m == L_STONE ? 840 : m == L_WATER ? 4186 : 1005) * t[i];
        }
        float[] z0 = new float[N];
        return new ColumnTask(cx, cz, mat, mass, t, z0, z0.clone(), z0.clone(), z0.clone(), z0.clone(), e,
                new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N]);
    }

    @Test
    void javaThreadsKeepAllocatingWhileANativeStepIsInFlight() throws Exception {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(EPOCH, LUT);
        List<ColumnTask> tasks = new ArrayList<>();
        for (int c = 0; c < 16; c++) tasks.add(column(c % 4, c / 4));

        AtomicLong stepEnd = new AtomicLong();
        Thread step = new Thread(() -> {
            engine.stepWorld(tasks, EPOCH, DT, PASSES, List.of());   // ONE call: one native step
            stepEnd.set(System.nanoTime());
        }, "orge-compute-test");
        long t0 = System.nanoTime();
        step.start();
        Thread.sleep(4000);   // let the call get past its Java-side marshalling into native code
        assumeTrue(step.isAlive(), "the native step finished in under 4 s — too fast to observe a stall");

        // Allocate a whole heap's worth of short-lived garbage, as a ticking server thread does: this
        // cannot finish without garbage collections.
        long garbage = 0;
        long need = Runtime.getRuntime().maxMemory();
        byte[] sink = null;
        while (garbage < need) {
            sink = new byte[64 * 1024];
            garbage += sink.length;
        }
        long gcDone = System.nanoTime();
        boolean stepStillRunning = step.isAlive();
        step.join();

        double gcS = (gcDone - t0) / 1e9, stepS = (stepEnd.get() - t0) / 1e9;
        System.out.printf("JniStepGcIT: %d MB allocated by %.2f s; native step ended at %.2f s%n", need >> 20, gcS, stepS);
        assertTrue(sink.length > 0 && stepStillRunning && gcDone < stepEnd.get(),
                String.format("allocating %d MB during a native step finished at %.2f s, the step ended at %.2f s"
                        + " — the allocations waited for the step (a JNI critical region held the heap)",
                        need >> 20, gcS, stepS));
    }
}
