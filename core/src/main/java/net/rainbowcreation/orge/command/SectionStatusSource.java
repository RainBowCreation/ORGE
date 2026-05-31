package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * Resolves the {@link LiveStatus} of a section for the live readout. Implemented in the mod entrypoint
 * as a thin closure over the section store (load/stored state) and the scheduler's active set
 * (active/dormant state), which the command layer otherwise has no handle on. Server-thread only.
 */
@FunctionalInterface
public interface SectionStatusSource {

    /** The lifecycle status of the section addressed by {@code key} in {@code dimension}. */
    LiveStatus statusOf(Identifier dimension, SubchunkKey key);
}
