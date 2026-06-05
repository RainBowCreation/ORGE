package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;

/**
 * Pure decision for the placement-injection capture. A block PLACE event ({@code wakeBlock}) is
 * captured (enqueued) when the cell carries an ORGE material, regardless of whether the placed species
 * matches the recorded incumbent:
 * <ul>
 *   <li>the cell is untracked ({@code incumbent == null}) — enqueue so the placement is made durable
 *       against the stale-write-back vanish race;</li>
 *   <li>the placed species id DIFFERS from the incumbent — displace-and-inject (the new block replaces
 *       whatever was there: fluid, solid, or anything else); see {@link #isDisplacement};</li>
 *   <li>the placed species id MATCHES the incumbent — a SAME-SPECIES placement (e.g. a water source on
 *       a cell the engine already records as water). This is enqueued too: the engine tops the cell up
 *       to the placed source mass IN PLACE (idempotent when already full). Without this, "place water on
 *       water" silently does nothing and repeated placement onto a tracked cell becomes unplaceable.</li>
 * </ul>
 * Only {@code live == null} (no ORGE material for the placed block) is NOT captured.
 *
 * <p>{@code movable()} is NOT consulted here. It governs only whether a substance flows after placement,
 * not whether the placement itself is captured.</p>
 *
 * <p>NOTE: {@code capture} is driven only from the block-event wake path ({@code wakeBlock}), NOT the
 * reconciler's steady-state write-back — so a same-species call here is a genuine placement event, not an
 * engine-output repaint, and is safe to enqueue as a top-up.</p>
 */
public final class PlacementInjectionPolicy {

    private PlacementInjectionPolicy() {
    }

    /**
     * Whether a placement event should be enqueued as an injection at all. True for any ORGE material
     * ({@code live != null}): a different/untracked species is a displace-and-inject, a same species is an
     * in-place top-up. The only non-injection is a non-ORGE placement ({@code live == null}).
     *
     * @param live      material of the newly-placed block at the cell (from the live world).
     * @param incumbent material of the cell's recorded engine-output species, or {@code null} if untracked.
     */
    public static boolean shouldInject(Material live, Material incumbent) {
        return live != null;
    }

    /**
     * Whether this placement DISPLACES a different incumbent (vs a same-species top-up or an untracked
     * cell). Retained for the diagnostic trace and callers that branch on cross-species relocation.
     *
     * @param live      material of the newly-placed block at the cell (from the live world).
     * @param incumbent material of the cell's recorded engine-output species (the thing to displace),
     *                  or {@code null} if the cell is untracked.
     */
    public static boolean isDisplacement(Material live, Material incumbent) {
        if (live == null) return false;
        if (incumbent == null) return true;           // untracked cell — enqueue for durability
        return !live.id().equals(incumbent.id());     // different species → displace-and-inject
    }
}
