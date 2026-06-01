package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Co-step expansion for antisymmetric seam-flux conservation (DESIGN §10 Phase-2b).
 *
 * <p>When the native engine transfers fluid mass across a section face it uses an antisymmetric
 * flux: section A subtracts the transfer; section B adds it. Mass is only conserved when BOTH
 * sides are stepped in the same batch. Without this helper a flow-active section's dormant
 * neighbour would miss its half of the seam exchange → mass leak.</p>
 *
 * <p>This class is pure (no Minecraft, no side effects) and headless-testable.</p>
 */
public final class SeamCoStep {

    private SeamCoStep() {}

    /**
     * Returns the co-step batch: every key in {@code active}, PLUS the 6 face-neighbours
     * (±cx, ±sectionY, ±cz) of every active key for which {@code isFlowActive} is true.
     *
     * <p>Deduplication: originals are preserved first (in input order), then added neighbours
     * appear in first-seen order. A neighbour that is already an original is not duplicated.
     * An added neighbour that happens to be unloaded is harmless — the existing
     * {@code loadedChunk}/{@code sectionOrNull} null-checks in the snapshot loop skip it.</p>
     *
     * <p>A co-stepped neighbour is NOT permanently woken: it enters this cycle's batch once
     * (transient inclusion) but its {@link SettleCountdown} is not touched here. If the
     * neighbour truly settled, {@code noteSettle} will return it toward sleep next cycle.</p>
     *
     * @param active       the keys already selected for this cycle's batch (originals)
     * @param isFlowActive predicate that returns {@code true} iff the section's flow pass is
     *                     non-dormant and therefore needs its 6 face-neighbours co-stepped
     * @return deduplicated list: originals first (input order), then added neighbours (first-seen)
     */
    public static List<SubchunkKey> expand(List<SubchunkKey> active,
                                           Predicate<SubchunkKey> isFlowActive) {
        // Legacy 2-arg form: full 6-face skirt for flow-active sections, no separate gas-column pass and
        // no world-height gate (the snapshot loop's null-checks already drop any out-of-world neighbour).
        return expand(active, isFlowActive, k -> false, y -> true);
    }

    /**
     * §11 co-step expansion with an explicit <b>gas-column</b> pass. In addition to the 6-face skirt of
     * every {@code isFlowActive} section, this also co-steps the section <b>directly above</b> every
     * section that has an active fluid/gas <b>surface</b> ({@code hasActiveSurface}), so rising/displaced
     * gas has a loaded receiver across the Y seam (else strict §9 conservation stalls the flow at the
     * seam). The above-section is added <em>only</em> when {@code sectionYWithinWorld} accepts
     * {@code sectionY + 1} — at the world's top section there is no section above, so none is added.
     *
     * <p>Like the skirt, the gas-column neighbour is a TRANSIENT batch member: its {@link SettleCountdown}
     * is untouched here, so a calm air column settles straight back to sleep via {@code noteSettle}.
     * Deduplication is by {@link LinkedHashSet}, so a section above that is also a flow-active skirt
     * neighbour appears exactly once.</p>
     *
     * @param active              the keys already selected (originals, preserved first in input order)
     * @param isFlowActive        true iff a section needs its full 6-face skirt co-stepped
     * @param hasActiveSurface    true iff a section has an active fluid/gas surface whose section ABOVE
     *                            must be co-stepped to receive rising/displaced gas
     * @param sectionYWithinWorld accepts a {@code sectionY} that is inside the loaded world height; the
     *                            section above is only added when {@code sectionY + 1} is accepted
     * @return deduplicated list: originals first (input order), then added neighbours (first-seen)
     */
    public static List<SubchunkKey> expand(List<SubchunkKey> active,
                                           Predicate<SubchunkKey> isFlowActive,
                                           Predicate<SubchunkKey> hasActiveSurface,
                                           IntPredicate sectionYWithinWorld) {
        // LinkedHashSet preserves insertion order and deduplicates.
        LinkedHashSet<SubchunkKey> set = new LinkedHashSet<>(active);

        for (SubchunkKey key : active) {
            int cx = key.cx();
            int sy = key.sectionY();
            int cz = key.cz();
            if (isFlowActive.test(key)) {
                set.add(new SubchunkKey(cx - 1, sy,     cz    ));
                set.add(new SubchunkKey(cx + 1, sy,     cz    ));
                set.add(new SubchunkKey(cx,     sy - 1, cz    ));
                set.add(new SubchunkKey(cx,     sy + 1, cz    ));
                set.add(new SubchunkKey(cx,     sy,     cz - 1));
                set.add(new SubchunkKey(cx,     sy,     cz + 1));
            }
            // §11 gas column: the section directly ABOVE an active fluid/gas surface, gated by world top.
            if (hasActiveSurface.test(key) && sectionYWithinWorld.test(sy + 1)) {
                set.add(new SubchunkKey(cx, sy + 1, cz));
            }
        }

        return new ArrayList<>(set);
    }
}
