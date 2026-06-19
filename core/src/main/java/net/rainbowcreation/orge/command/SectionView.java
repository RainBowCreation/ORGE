package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionData;

/** Immutable read view of one section's per-cell thermal state, for observability. */
public interface SectionView {

    /** Temperature (K) of cell {@code cell} (0..4095). */
    float tempAt(int cell);

    /** Mass (kg) of cell {@code cell} (0..4095). */
    float massAt(int cell);

    /**
     * The STORED per-cell material id of cell {@code cell} (0..4095) — the sim's authoritative species
     * (e.g. {@code orge:vacuum} for a broken/drained cell), NOT the live Minecraft block's first-touch
     * mapping. Observability reads this so {@code /orge get} reflects sim truth after a break.
     */
    Identifier material(int cell);

    /** Storage form (UNIFORM/FULL) of the underlying section. */
    SectionData.Form form();

    /** True when this is the synthesized never-simulated ambient baseline (not stored/evolved). */
    boolean ambient();
}
