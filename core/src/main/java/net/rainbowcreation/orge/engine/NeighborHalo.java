package net.rainbowcreation.orge.engine;

/**
 * The one-cell temperature halo around a section, one 16×16 face per side
 * (DESIGN.md §2/§8). Used by the solver to compute conduction across section
 * boundaries without owning the neighbouring sections.
 *
 * <p>Each face array is length 256 (16×16). Faces are named by the axis direction
 * they sit on.</p>
 */
public record NeighborHalo(
        float[] negX, float[] posX,
        float[] negY, float[] posY,
        float[] negZ, float[] posZ
) {
    public static final int FACE_CELLS = 256;
}
