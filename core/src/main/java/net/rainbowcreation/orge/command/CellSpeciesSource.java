package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * Resolves a cell's LIVE species — the world block's first-touch material — for a {@code /orge set}
 * or {@code /orge fill} write that lands on a not-yet-simulated cell. Without it the write targets a
 * synthesized ambient section whose durable material is the {@code orge:vacuum} sentinel, so the
 * temperature encode (E = m·h(T)) runs against an enthalpy-less species and stores 0 J — the edit is
 * silently lost. Returns {@code null} when unresolvable (no bound server, or the chunk/section is not
 * loaded), in which case the sink leaves identity untouched. Server-thread only.
 */
@FunctionalInterface
public interface CellSpeciesSource {

    /** The live world species id for the cell at {@code key}+{@code cell}, or {@code null}. */
    Identifier speciesAt(Identifier dimension, SubchunkKey key, int cell);
}
