package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorStepRunnerTest {

    @Test
    void runsTheTaskAndExposesResult() throws Exception {
        ExecutorStepRunner runner = new ExecutorStepRunner();
        try {
            float[] arr = {1f, 2f};
            StepRunner.Handle h = runner.submit(() -> List.of(arr));
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!h.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(h.isDone(), "task should finish well within 2s");
            assertSame(arr, h.result().get(0));
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void failedTaskSurfacesAsRuntimeFromResult() throws Exception {
        ExecutorStepRunner runner = new ExecutorStepRunner();
        try {
            StepRunner.Handle h = runner.submit(() -> { throw new IllegalStateException("boom"); });
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!h.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(h.isDone());
            assertThrows(RuntimeException.class, h::result);
        } finally {
            runner.shutdown();
        }
    }
}
