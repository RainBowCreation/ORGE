package net.rainbowcreation.orge.command;

import net.rainbowcreation.orge.section.SectionData;

/** Immutable read view of one section's per-cell thermal state, for observability. */
public interface SectionView {

    /** Temperature (K) of cell {@code cell} (0..4095). */
    float tempAt(int cell);

    /** Mass (kg) of cell {@code cell} (0..4095). */
    float massAt(int cell);

    /** Storage form (UNIFORM/FULL) of the underlying section. */
    SectionData.Form form();

    /** True when this is the synthesized never-simulated ambient baseline (not stored/evolved). */
    boolean ambient();
}
