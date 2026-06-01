package net.rainbowcreation.orge.engine;

/**
 * The one-cell neighbour halo around a section — one 16×16 face per side
 * (DESIGN.md §2/§8). Each face carries the neighbour cells' temperatures
 * <b>and</b> material indices: the boundary conductivity {@code k_eff} is a
 * harmonic mean of the two cells' conductivities, so the solver needs the
 * neighbour's material to look it up in the LUT.
 *
 * <p>Each face array is length {@link #FACE_CELLS} (16×16). Faces are named by the
 * axis direction they sit on. A halo cell whose material has conductivity ≤ 0
 * (e.g. void) carries no flux — which is also how an absent / world-edge
 * neighbour is encoded.</p>
 *
 * <p>Face cell index: X-faces {@code y + 16*z}, Y-faces {@code x + 16*z},
 * Z-faces {@code x + 16*y}.</p>
 */
public record NeighborHalo(
        float[] negXT, float[] posXT, float[] negYT, float[] posYT, float[] negZT, float[] posZT,
        char[]  negXM, char[]  posXM, char[]  negYM, char[]  posYM, char[]  negZM, char[]  posZM,
        float[] negXMass, float[] posXMass, float[] negYMass, float[] posYMass, float[] negZMass, float[] posZMass
) {
    public static final int FACE_CELLS = 256;

    /**
     * An all-void halo: every face is zero temperature / void material ({@code 0}) / 0 kg — encodes
     * "no neighbour on any side" (a world-edge / absent-column wall). Used by the whole-region column
     * path, which carries cross-column flow in the engine World rather than in per-section halos, so its
     * reconstructed per-section {@link StepTask}s need a valid-but-inert halo.
     */
    public static NeighborHalo empty() {
        return new NeighborHalo(
                new float[FACE_CELLS], new float[FACE_CELLS], new float[FACE_CELLS],
                new float[FACE_CELLS], new float[FACE_CELLS], new float[FACE_CELLS],
                new char[FACE_CELLS], new char[FACE_CELLS], new char[FACE_CELLS],
                new char[FACE_CELLS], new char[FACE_CELLS], new char[FACE_CELLS],
                new float[FACE_CELLS], new float[FACE_CELLS], new float[FACE_CELLS],
                new float[FACE_CELLS], new float[FACE_CELLS], new float[FACE_CELLS]);
    }

    /** Temperature faces in the canonical order negX, posX, negY, posY, negZ, posZ. */
    public float[][] tempFaces() {
        return new float[][]{negXT, posXT, negYT, posYT, negZT, posZT};
    }

    /** Material-index faces in the canonical order negX, posX, negY, posY, negZ, posZ. */
    public char[][] matFaces() {
        return new char[][]{negXM, posXM, negYM, posYM, negZM, posZM};
    }

    /** Neighbour-mass faces (kg) in the canonical order negX, posX, negY, posY, negZ, posZ. */
    public float[][] massFaces() {
        return new float[][]{negXMass, posXMass, negYMass, posYMass, negZMass, posZMass};
    }
}
