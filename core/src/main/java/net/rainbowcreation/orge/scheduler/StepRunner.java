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
    }
}
