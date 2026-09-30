package net.rainbowcreation.orge.scheduler;

/**
 * Step timing metrics for {@code /orge perf}: does the engine keep up with real time? Owned and
 * updated by {@link Scheduler} on the server thread (no locking). Plain fields; runtime-only.
 */
public final class StepMetrics {

    public static final double ALPHA = 0.2;

    public double nativeLastMs, nativeEmaMs, nativeMaxMs;
    public double serverLastMs, serverEmaMs;
    public int latencyLastTicks;
    public double latencyEmaTicks;
    public long completed, late, cancelled, held;
    /** Simulated seconds of completed steps vs real seconds of advancing (non-frozen) ticks. */
    public double simSeconds;
    public long realTicks;

    /** Exponential moving average; the first sample (count 0) seeds it. */
    static double ema(double prev, double x, long samplesBefore) {
        return samplesBefore == 0 ? x : prev + ALPHA * (x - prev);
    }

    void onComplete(double nativeMs, double serverMs, int latencyTicks, boolean metDeadline, double dt) {
        nativeLastMs = nativeMs;
        nativeEmaMs = ema(nativeEmaMs, nativeMs, completed);
        nativeMaxMs = Math.max(nativeMaxMs, nativeMs);
        serverLastMs = serverMs;
        serverEmaMs = ema(serverEmaMs, serverMs, completed);
        latencyLastTicks = latencyTicks;
        latencyEmaTicks = ema(latencyEmaTicks, latencyTicks, completed);
        if (!metDeadline) late++;
        simSeconds += dt;
        completed++;
    }

    void onRealTick() { realTicks++; }
    void onCancel() { cancelled++; }
    void onHeld() { held++; }

    /** sim / real seconds: 1.0 = real time, below = falling behind (slow-motion). 1.0 before any real time. */
    public double ratio() {
        return realTicks == 0 ? 1.0 : simSeconds / (realTicks / 20.0);
    }

    /**
     * Keeping up = EMA native + EMA server ms fit the step interval budget AND the sim/real ratio is
     * ≥ 95% of the configured time-scale (1.0 in AUTO; a FIXED dt is a deliberate slow/fast-motion).
     */
    public boolean keepingUp(double budgetMs, double timeScale) {
        return nativeEmaMs + serverEmaMs < budgetMs && ratio() >= 0.95 * timeScale;
    }

    public void reset() {
        nativeLastMs = nativeEmaMs = nativeMaxMs = serverLastMs = serverEmaMs = latencyEmaTicks = simSeconds = 0;
        latencyLastTicks = 0;
        completed = late = cancelled = held = realTicks = 0;
    }
}
