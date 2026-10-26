package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.StepResult;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Runs one conduction step off the server thread. Abstracted so the {@link Scheduler} can be
 * driven deterministically in tests (a fake runner) while production uses a real background
 * thread ({@link ExecutorStepRunner}).
 */
public interface StepRunner {

    /** Submit a step; the returned handle is polled by the scheduler on later ticks. */
    Handle submit(Callable<List<StepResult>> task);

    /** A submitted step in flight. */
    interface Handle {
        /** True once the task has finished (successfully or not). Non-blocking. */
        boolean isDone();

        /**
         * The completed result. Precondition: {@link #isDone()} is true.
         * @throws RuntimeException if the task threw (the cause is attached).
         */
        List<StepResult> result();

        /** Best-effort cancel of a not-yet-finished step. */
        void cancel();

        /**
         * True once the runner has begun executing the step. A started step is never cancelled for being
         * slow (issue #51): a running native step cannot be interrupted, so cancelling it only discards its
         * result. Default {@code false}: a handle that does not track this reads as not yet begun.
         */
        default boolean started() {
            return false;
        }

        /**
         * Cancel the step only if the runner has not begun it, atomically with its start: {@code true} when
         * it never runs, {@code false} when it had already started (it then runs to completion). Default:
         * {@link #cancel()} for a handle that does not track starts.
         */
        default boolean cancelIfNotStarted() {
            cancel();
            return true;
        }
    }
}
