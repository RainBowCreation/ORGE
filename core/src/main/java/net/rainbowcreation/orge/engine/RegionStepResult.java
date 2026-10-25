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
                               float[] injectedE, float[] sealedE,
                               double[] books) {

    // {@code books} (issue #44) = this step's closing ledgers from the engine's per-call World, which
    // otherwise die with it: {@code [BOOKS_BOUNDARY_E]} boundaryE [J] (the #23 drain, floor-clamp and
    // ε-sink energy), {@code [BOOKS_DAMP_HEAT]} / {@code [BOOKS_SWAP_PE]} the in-domain dissipation heat
    // deposited into E [J], then {@code boundaryMassEps[species]} [kg] from {@code BOOKS_HEAD}. Per step:
    // Δ(Σ cell E) + boundaryE − dampHeat − swapPeHeat = 0. Layout mirrors {@code ORGE_BOOKS_*} in
    // {@code orge_step_world.hpp}.
    public static final int BOOKS_BOUNDARY_E = 0;
    public static final int BOOKS_DAMP_HEAT = 1;
    public static final int BOOKS_SWAP_PE = 2;
    public static final int BOOKS_HEAD = 3;

    /** Convenience — an engine that books nothing (all-zero books). */
    public RegionStepResult(List<ColumnResult> columns,
                            float[] injected, float[] sealedLoss,
                            float[] injectedE, float[] sealedE) {
        this(columns, injected, sealedLoss, injectedE, sealedE, new double[BOOKS_HEAD + injected.length]);
    }

    /** Energy booked to the boundary this step [J]. */
    public double boundaryE() { return books[BOOKS_BOUNDARY_E]; }

    /** In-domain dissipation heat (damping + swap potential energy) deposited into cell E this step [J]. */
    public double dissipationE() { return books[BOOKS_DAMP_HEAT] + books[BOOKS_SWAP_PE]; }

    /** Mass of species {@code s} emptied to the ε-mass sink this step [kg] (0 past the booked range). */
    public double boundaryMassEps(int s) {
        int i = BOOKS_HEAD + s;
        return i < books.length ? books[i] : 0.0;
    }
}
