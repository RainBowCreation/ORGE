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
    /** Issue #51: a step is either never run or never cancelled — a started step reports started and refuses
     *  {@code cancelIfNotStarted}; one still queued behind it is cancelled and never runs. */
    @Test
    void cancelIfNotStartedCancelsOnlyAStepThatNeverBegan() throws Exception {
        ExecutorStepRunner runner = new ExecutorStepRunner();
        try {
            java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean queuedRan = new java.util.concurrent.atomic.AtomicBoolean();
            StepRunner.Handle first = runner.submit(() -> {
                running.countDown();
                release.await();
                return List.of();
            });
            StepRunner.Handle queued = runner.submit(() -> {
                queuedRan.set(true);
                return List.of();
            });
            assertTrue(running.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(first.started(), "the step thread is running the first step");
            assertFalse(first.cancelIfNotStarted(), "a started step is not cancelled");
            assertFalse(queued.started(), "the second is still queued");
            assertTrue(queued.cancelIfNotStarted(), "a queued step is cancelled");
            release.countDown();
            awaitDone(first);
            assertEquals(List.of(), first.result(), "the started step ran to completion");
            awaitDone(queued);
            Thread.sleep(50);
            assertFalse(queuedRan.get(), "the cancelled step never ran");
        } finally {
            runner.shutdown();
        }
    }
}
