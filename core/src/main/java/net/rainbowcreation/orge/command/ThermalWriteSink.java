package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

/** Server-authoritative single-cell writes. Writes only ever target the server store. */
public interface ThermalWriteSink {

    /** Whether the column owning {@code key} is loaded (precondition for a durable write). */
    boolean isLoaded(Identifier dimension, SubchunkKey key);

    /** Write temperature (K) into one cell, marking the column dirty. */
    void writeTemp(Identifier dimension, SubchunkKey key, int cell, float kelvin);

    /** Write mass (kg) into one cell, marking the column dirty. */
    void writeMass(Identifier dimension, SubchunkKey key, int cell, float kg);
}
