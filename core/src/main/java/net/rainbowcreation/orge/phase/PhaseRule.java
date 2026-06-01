package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

import java.util.Optional;

/**
 * The pure §7 phase-change decision: given a cell's new temperature and its current
 * {@link Material}, return the id of the <b>material</b> it should become, or empty. Boiling is
 * checked first; both tests use strict inequalities, so a cell exactly at a threshold is a
 * no-op. Null targets / ±∞ default thresholds (the record defaults) never transition.
 *
 * <p>{@code maxTarget}/{@code minTarget} are <b>material ids</b> (e.g. {@code orge:steam},
 * {@code orge:ice}) — the cell's new identity. The block actually drawn is a <em>separate</em>
 * material → {@code representative_block} lookup (see {@link PhaseRenderResolver}); identity lives
 * in the material, never in the block. No Minecraft world access — fully unit-testable.</p>
 *
 * <p>Assumes a finite temperature — §9 ({@code StepValidator}) replaces any NaN/±Inf before
 * the scheduler writes back, so this runs only on clean values.</p>
 */
public final class PhaseRule {

    private PhaseRule() {}

    /** The target MATERIAL id the cell should become, or empty if it stays put. */
    public static Optional<Identifier> targetMaterial(float temperatureK, Material current) {
        if (current.maxTarget() != null && temperatureK > current.maxTemp()) {
            return Optional.of(current.maxTarget());
        }
        if (current.minTarget() != null && temperatureK < current.minTemp()) {
            return Optional.of(current.minTarget());
        }
        return Optional.empty();
    }
}
