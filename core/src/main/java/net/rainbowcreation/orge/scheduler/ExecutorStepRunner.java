package net.rainbowcreation.orge.scheduler;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@link StepRunner} backed by a single daemon background thread — the one server-side
 * fallback engine instance (DESIGN §3 keeps exactly one). Daemon so it never blocks JVM exit.
 */
public final class ExecutorStepRunner implements StepRunner {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orge-conduction");
        t.setDaemon(true);
        return t;
    });

    @Override
    public Handle submit(Callable<List<float[]>> task) {
        Future<List<float[]>> future = executor.submit(task);
        return new Handle() {
            @Override
            public boolean isDone() {
                return future.isDone();
            }

            @Override
            public List<float[]> result() {
                if (!future.isDone()) {
                    throw new IllegalStateException("result() called before isDone()");
                }
                try {
                    return future.get();
                } catch (ExecutionException e) {
                    throw new RuntimeException("conduction step failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted awaiting step result", e);
                }
            }

            @Override
            public void cancel() {
                future.cancel(true);
            }
        };
    }

    /** Stop the background thread (call on server stop). */
    public void shutdown() {
        executor.shutdownNow();
    }
}
