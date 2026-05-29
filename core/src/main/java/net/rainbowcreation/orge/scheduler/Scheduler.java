package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Map;

/**
 * The per-tick server scheduler (DESIGN.md §8). Runs on the logical server each tick:
 *
 * <ol>
 *   <li>Build the union of all players' section-spheres (+ force-loaded regions).</li>
 *   <li>Assign each <i>unique</i> subchunk to the nearest healthy worker whose range
 *       covers it (best locality, single owner ⇒ natural dedup).</li>
 *   <li>Overflow an overloaded/throttled owner's subchunk to the next-nearest covering
 *       worker, else to the server fallback engine.</li>
 *   <li>Send each worker its assignments; geometry once per section version, mutable
 *       temperatures + halo every tick.</li>
 *   <li>A subchunk that misses the 1 s deadline holds its previous temperatures for a
 *       tick (no recompute storm).</li>
 * </ol>
 */
public final class Scheduler {

    /** dt and cadence (DESIGN.md §4): step once per real second = every 20 ticks. */
    public static final int TICKS_PER_STEP = 20;
    public static final double STEP_DT_SECONDS = 1.0;

    // TODO(phase: scheduler): worker registry, per-player sphere union, nearest-owner
    //  assignment with overflow, and the deadline/hold-previous bookkeeping.

    /** Compute this tick's subchunk → owning worker assignment. */
    public Map<SubchunkKey, Worker> assign() {
        throw new UnsupportedOperationException("Phase 1 skeleton: scheduler not yet implemented");
    }
}
