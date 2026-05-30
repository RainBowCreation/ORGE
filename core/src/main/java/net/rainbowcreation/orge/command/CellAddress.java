package net.rainbowcreation.orge.command;

import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * Pure mapping from a world block position to the ORGE cell that holds its thermal state:
 * the containing {@link SubchunkKey} plus the section-local cell index. The cell index is
 * the exact inverse of {@code LiveMaterials.blockAt} (x-fastest, {@code x + 16*y + 256*z}),
 * so the command addresses the same cell the engine indexes. ({@code >>4} on an {@code int}
 * equals {@code Math.floorDiv(_,16)} and is correct for negative coordinates.)
 */
public record CellAddress(SubchunkKey key, int cell) {

    public static CellAddress of(int x, int y, int z) {
        SubchunkKey key = new SubchunkKey(x >> 4, y >> 4, z >> 4);
        int cell = (x & 15) | ((y & 15) << 4) | ((z & 15) << 8);
        return new CellAddress(key, cell);
    }
}
