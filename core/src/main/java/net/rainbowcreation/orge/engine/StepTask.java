package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * One section's input to a single conduction step (DESIGN.md §2).
 *
 * <p>Geometry ({@code matIx}, {@code mass}) is sent once per section version and cached
 * by the worker; only the mutable {@code temperature} + neighbour {@code halo} change
 * each tick (DESIGN.md §8). Arrays are flat, length 4096 (16³), x-fastest.</p>
 *
 * @param key         which section
 * @param matIx       material LUT index per cell (length 4096)
 * @param mass        kg per cell (length 4096)
 * @param temperature K per cell at the start of the step (length 4096)
 * @param halo        one-cell neighbour temperatures around the section (6 faces)
 */
public record StepTask(
        SubchunkKey key,
        char[] matIx,
        float[] mass,
        float[] temperature,
        NeighborHalo halo
) {
}
