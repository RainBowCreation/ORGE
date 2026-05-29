package net.rainbowcreation.orge.scheduler;

/**
 * A compute worker in the server-orchestrated pool (DESIGN.md §3/§8) — a connected
 * client, or the single server fallback engine instance.
 *
 * <p>Carries the health-throttle state: a worker that delivers late/incomplete drops
 * its {@code range} by 1 (to a minimum); after K consecutive on-time ticks under a
 * compute-time budget it climbs back by 1 toward the configured default/max.</p>
 */
public final class Worker {

    public static final int MIN_RANGE = 1;

    private final java.util.UUID id;
    private final boolean serverFallback;

    private int range;
    private int onTimeStreak;

    public Worker(java.util.UUID id, boolean serverFallback, int initialRange) {
        this.id = id;
        this.serverFallback = serverFallback;
        this.range = Math.max(MIN_RANGE, initialRange);
    }

    public java.util.UUID id() {
        return id;
    }

    public boolean isServerFallback() {
        return serverFallback;
    }

    public int range() {
        return range;
    }

    // TODO(phase: scheduler): wire onTimeStreak/range adjustment to reported HEALTH
    //  (drop on late/incomplete; climb after K on-time ticks under budget).
    public void reportLate() {
        onTimeStreak = 0;
        range = Math.max(MIN_RANGE, range - 1);
    }
}
