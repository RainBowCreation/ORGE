package net.rainbowcreation.orge.phase;

/**
 * Phase-change evaluation (DESIGN.md §7), run server-side at the second boundary after
 * reading back new temperatures.
 *
 * <p>If a cell crosses its material's {@code boilingPoint}/{@code freezingPoint}, its
 * block becomes the target material's representative block, carrying <b>mass and final
 * temperature</b> across unchanged (mass conserved exactly). No latent-heat plateau in
 * v1. Gas targets (e.g. water → {@code orge:steam}) use inert {@code orge:} gas blocks
 * that conserve mass/temperature but have no buoyancy/flow until Phase 2.</p>
 */
public final class PhaseChange {

    private PhaseChange() {
    }

    // TODO(phase: phase-change): given a section's new temperatures + per-cell material,
    //  emit the set of (BlockPos -> representative block, carried mass + T) replacements
    //  for the server to apply. Validate finite + clamp before trusting client results
    //  (DESIGN.md §9) upstream of this call.
}
