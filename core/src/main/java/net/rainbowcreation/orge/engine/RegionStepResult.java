package net.rainbowcreation.orge.engine;

import java.util.List;

/**
 * Result of an injection-aware {@link OrgeEngine#stepWorld(List, List, double, int, List)} call:
 * the next-state columns (same order as the input) plus the per-species placement ledger the §9
 * gate needs. {@code injected[s]} = Σ placed mass for species {@code s}; {@code sealedLoss[s]} =
 * Σ incumbent mass deleted when no escape existed. Both are indexed by LUT species index and have
 * length {@code lut.size()}. For non-injecting engines both are all-zero.
 */
public record RegionStepResult(List<ColumnResult> columns, float[] injected, float[] sealedLoss) {
}
