package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorStepRunnerTest {

    private static void awaitDone(StepRunner.Handle h) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!h.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(h.isDone(), "task should finish well within 2s");
    }

    @Test
    void runsTheTaskAndExposesResult() throws Exception {
        ExecutorStepRunner runner = new ExecutorStepRunner();
        try {
            float[] arr = {1f, 2f};
            StepRunner.Handle h = runner.submit(() -> List.of(arr));
            awaitDone(h);
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
            awaitDone(h);
            assertThrows(RuntimeException.class, h::result);
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void resultBeforeDoneThrows() {
        ExecutorStepRunner runner = new ExecutorStepRunner();
        try {
            // A task that blocks so the handle is observably not-done when we call result().
            StepRunner.Handle h = runner.submit(() -> {
                Thread.sleep(500);
                return java.util.List.of(new float[]{1f});
            });
            assertThrows(IllegalStateException.class, h::result);
        } finally {
            runner.shutdown();
        }
    }
}
