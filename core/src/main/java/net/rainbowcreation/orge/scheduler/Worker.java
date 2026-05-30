package net.rainbowcreation.orge.scheduler;

import java.util.UUID;

/**
 * A compute worker in the server-orchestrated pool (DESIGN §3/§8). In the single-node v1
 * there is exactly one, the {@code serverFallback} worker (the server itself).
 *
 * <p>Health throttle (DESIGN §8): a step that misses its deadline or runs over the compute
 * budget drops {@code range} by 1 (to {@link #MIN_RANGE}); after {@code ticksToClimb}
 * consecutive on-time, under-budget steps the range climbs by 1 (to {@code maxRange}).
 * The budget is the per-cycle <b>server-thread</b> wall-time ORGE consumes (snapshot +
 * write-back/reconcile), so the throttle backs off on the cost that actually competes with
 * the game tick — not the off-thread native engine step.</p>
 */
public final class Worker {

    public static final int MIN_RANGE = 1;

    private final UUID id;
    private final boolean serverFallback;
    private final int maxRange;
    private final double budgetMillis;
    private final int ticksToClimb;

    private int range;
    private int onTimeStreak;

    public Worker(UUID id, boolean serverFallback, int initialRange,
                  int maxRange, double budgetMillis, int ticksToClimb) {
        this.id = id;
        this.serverFallback = serverFallback;
        this.maxRange = Math.max(MIN_RANGE, maxRange);
        this.budgetMillis = budgetMillis;
        this.ticksToClimb = Math.max(1, ticksToClimb);
        this.range = clampRange(initialRange);
    }

    public UUID id() {
        return id;
    }

    public boolean isServerFallback() {
        return serverFallback;
    }

    public int range() {
        return range;
    }

    public int onTimeStreak() {
        return onTimeStreak;
    }

    /** A step missed its deadline (or was cancelled): drop range, reset streak. */
    public void reportLate() {
        onTimeStreak = 0;
        range = clampRange(range - 1);
    }

    /**
     * Records a completed step. {@code metDeadline} = the result arrived within the 1 s
     * deadline; {@code millis} = the per-cycle <b>server-thread</b> wall-time ORGE spent this
     * cycle (snapshot + write-back/reconcile), NOT the off-thread native engine step. Drops
     * range on a late step or one whose time is <b>strictly over</b> the budget
     * ({@code millis > budgetMillis}; exactly-at-budget is healthy); otherwise advances the
     * on-time streak and climbs after {@code ticksToClimb} consecutive healthy steps.
     */
    public void noteStep(double millis, boolean metDeadline) {
        if (!metDeadline || millis > budgetMillis) {
            reportLate();
            return;
        }
        onTimeStreak++;
        if (onTimeStreak >= ticksToClimb) {
            onTimeStreak = 0;
            range = clampRange(range + 1);
        }
    }

    private int clampRange(int r) {
        return Math.max(MIN_RANGE, Math.min(maxRange, r));
    }
}
