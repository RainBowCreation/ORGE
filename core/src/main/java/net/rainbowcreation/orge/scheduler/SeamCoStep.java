package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        // LinkedHashSet preserves insertion order and deduplicates.
        LinkedHashSet<SubchunkKey> set = new LinkedHashSet<>(active);

        for (SubchunkKey key : active) {
            if (!isFlowActive.test(key)) {
                continue;
            }
            int cx = key.cx();
            int sy = key.sectionY();
            int cz = key.cz();
            set.add(new SubchunkKey(cx - 1, sy,     cz    ));
            set.add(new SubchunkKey(cx + 1, sy,     cz    ));
            set.add(new SubchunkKey(cx,     sy - 1, cz    ));
            set.add(new SubchunkKey(cx,     sy + 1, cz    ));
            set.add(new SubchunkKey(cx,     sy,     cz - 1));
            set.add(new SubchunkKey(cx,     sy,     cz + 1));
        }

        return new ArrayList<>(set);
    }
}
