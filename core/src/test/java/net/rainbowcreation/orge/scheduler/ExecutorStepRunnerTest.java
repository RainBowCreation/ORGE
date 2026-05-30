package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.StepResult;
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
            StepRunner.Handle h = runner.submit(() -> List.of(new StepResult(arr, new float[]{0f, 0f})));
            awaitDone(h);
            assertSame(arr, h.result().get(0).temperature());
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
    void submitAfterShutdownRecreatesExecutor() throws Exception {
        // The runner outlives a single integrated-server lifecycle: shutdown() runs on
        // SERVER_STOPPING, but in singleplayer the player can start another world in the
        // same JVM. A submit after shutdown must run the step (re-create the executor),
        // not throw RejectedExecutionException.
        ExecutorStepRunner runner = new ExecutorStepRunner();
        try {
            float[] first = {1f};
            StepRunner.Handle h1 = runner.submit(() -> List.of(new StepResult(first, new float[]{0f})));
            awaitDone(h1);
            assertSame(first, h1.result().get(0).temperature());

            runner.shutdown(); // simulate SERVER_STOPPING

            float[] second = {2f};
            StepRunner.Handle h2 = runner.submit(() -> List.of(new StepResult(second, new float[]{0f}))); // simulate next world's tick
            awaitDone(h2);
            assertSame(second, h2.result().get(0).temperature());
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
                return java.util.List.of(new StepResult(new float[]{1f}, new float[]{0f}));
            });
            assertThrows(IllegalStateException.class, h::result);
        } finally {
            runner.shutdown();
        }
    }
}
