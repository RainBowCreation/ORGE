package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

import java.util.Optional;

/**
 * The pure §7 phase-change decision: given a cell's new temperature and its current
 * {@link Material}, return the id of the block it should become, or empty. Boiling is
 * checked first; both tests use strict inequalities, so a cell exactly at a threshold is a
 * no-op. Null targets / ±∞ default thresholds (the record defaults) never transition.
 *
 * <p>{@code maxTarget}/{@code minTarget} are the <b>block id to place</b> (the
 * resulting block's own thermal behaviour and reverse transition come from its binding/
 * material). No Minecraft world access — fully unit-testable.</p>
 *
 * <p>Assumes a finite temperature — §9 ({@code StepValidator}) replaces any NaN/±Inf before
 * the scheduler writes back, so this runs only on clean values.</p>
 */
public final class PhaseRule {

    private PhaseRule() {}

    public static Optional<Identifier> targetBlock(float temperatureK, Material current) {
        if (current.maxTarget() != null && temperatureK > current.maxTemp()) {
            return Optional.of(current.maxTarget());
        }
        if (current.minTarget() != null && temperatureK < current.minTemp()) {
            return Optional.of(current.minTarget());
        }
        return Optional.empty();
    }
}
