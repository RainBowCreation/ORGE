package net.rainbowcreation.orge.engine;

import java.util.List;

/**
 * Result of an injection-aware {@link OrgeEngine#stepWorld(List, List, double, int, List)} call:
 * the next-state columns (same order as the input) plus the per-species placement ledger the §9
 * gate needs. {@code injected[s]} = Σ placed mass for species {@code s}; {@code sealedLoss[s]} =
 * Σ incumbent mass deleted when no escape existed. {@code injectedE[s]} / {@code sealedE[s]} are the
 * matching ENERGY side (law #9 / §9 boundary ledger): Σ enthalpy placed and Σ enthalpy destroyed
 * with the sealed mass, so place/break energy is never silently dropped. All four are indexed by LUT
 * species index and have length {@code lut.size()}. For non-injecting engines all four are all-zero.
 *
 * <p>The momentum side of the boundary ledger is N/A this arc: injections carry no velocity, so
 * place/break momentum is structurally zero and is not ledgered (matches the C++ {@code LedgerAccum},
 * which has no momentum field).</p>
 */
public record RegionStepResult(List<ColumnResult> columns,
                               float[] injected, float[] sealedLoss,
                               float[] injectedE, float[] sealedE) {
}
