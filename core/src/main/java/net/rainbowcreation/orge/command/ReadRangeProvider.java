package net.rainbowcreation.orge.command;

/**
 * Supplies the configured section read-range (in sections) that bounds non-op
 * get/section. v1 returns a constant ({@code Scheduler.MAX_RANGE}); a future server-config
 * value and the client-side range calc read through this same seam.
 */
@FunctionalInterface
public interface ReadRangeProvider {
    int sectionReadRange();
}
