package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.NeighborHalo;

/**
 * Builds a {@link NeighborHalo} from the six neighbouring sections (or {@code null} at a
 * world/loaded edge → a void face). Face layouts and index conventions match §2 exactly:
 * {@code sidx(x,y,z)=x+16y+256z}; X-faces indexed {@code y+16z}, Y-faces {@code x+16z},
 * Z-faces {@code x+16y}; face order {@code negX,posX,negY,posY,negZ,posZ}. The contributing
 * plane of each neighbour is the layer of cells touching our section (e.g. the {@code -X}
 * neighbour's {@code x=15} layer).
 */
public final class HaloAssembler {

    /** Cells in a section (16³) — neighbour arrays are section-sized, indexed by {@link #sidx}. */
    private static final int SECTION_CELLS = 4096;

    /** A neighbouring section's data needed to fill one halo face. */
    public record Neighbor(float[] temperature, char[] matIx, float[] mass) {

        /**
         * An absent neighbour: all-zero, section-sized ({@value SECTION_CELLS}) arrays — no
         * temperature, void material, 0 kg. Equivalent to a {@code null} neighbour (no flux, no
         * mass flows in), but usable where a non-null {@link Neighbor} is required.
         */
        public static Neighbor absent() {
            return new Neighbor(
                    new float[SECTION_CELLS],
                    new char[SECTION_CELLS],
                    new float[SECTION_CELLS]);
        }
    }

    private HaloAssembler() {}

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    public static NeighborHalo assemble(Neighbor negX, Neighbor posX,
                                        Neighbor negY, Neighbor posY,
                                        Neighbor negZ, Neighbor posZ) {
        float[] negXT = new float[NeighborHalo.FACE_CELLS];
        float[] posXT = new float[NeighborHalo.FACE_CELLS];
        float[] negYT = new float[NeighborHalo.FACE_CELLS];
        float[] posYT = new float[NeighborHalo.FACE_CELLS];
        float[] negZT = new float[NeighborHalo.FACE_CELLS];
        float[] posZT = new float[NeighborHalo.FACE_CELLS];
        char[] negXM = new char[NeighborHalo.FACE_CELLS];
        char[] posXM = new char[NeighborHalo.FACE_CELLS];
        char[] negYM = new char[NeighborHalo.FACE_CELLS];
        char[] posYM = new char[NeighborHalo.FACE_CELLS];
        char[] negZM = new char[NeighborHalo.FACE_CELLS];
        char[] posZM = new char[NeighborHalo.FACE_CELLS];
        float[] negXMass = new float[NeighborHalo.FACE_CELLS];
        float[] posXMass = new float[NeighborHalo.FACE_CELLS];
        float[] negYMass = new float[NeighborHalo.FACE_CELLS];
        float[] posYMass = new float[NeighborHalo.FACE_CELLS];
        float[] negZMass = new float[NeighborHalo.FACE_CELLS];
        float[] posZMass = new float[NeighborHalo.FACE_CELLS];

        // X faces: face index = y + 16*z; neighbour plane x = 15 (negX) / x = 0 (posX).
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                int f = y + 16 * z;
                copyCell(negX, sidx(15, y, z), negXT, negXM, negXMass, f);
                copyCell(posX, sidx(0, y, z), posXT, posXM, posXMass, f);
            }
        }
        // Y faces: face index = x + 16*z; neighbour plane y = 15 (negY) / y = 0 (posY).
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int f = x + 16 * z;
                copyCell(negY, sidx(x, 15, z), negYT, negYM, negYMass, f);
                copyCell(posY, sidx(x, 0, z), posYT, posYM, posYMass, f);
            }
        }
        // Z faces: face index = x + 16*y; neighbour plane z = 15 (negZ) / z = 0 (posZ).
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int f = x + 16 * y;
                copyCell(negZ, sidx(x, y, 15), negZT, negZM, negZMass, f);
                copyCell(posZ, sidx(x, y, 0), posZT, posZM, posZMass, f);
            }
        }

        return new NeighborHalo(
                negXT, posXT, negYT, posYT, negZT, posZT,
                negXM, posXM, negYM, posYM, negZM, posZM,
                negXMass, posXMass, negYMass, posYMass, negZMass, posZMass);
    }

    /** Copies one source cell into a face slot; a {@code null} neighbour leaves the void default (0). */
    private static void copyCell(Neighbor n, int srcIndex,
                                 float[] tFace, char[] mFace, float[] massFace, int faceIndex) {
        if (n == null) {
            return; // arrays default to 0f / 0 (void) / 0 kg
        }
        tFace[faceIndex] = n.temperature()[srcIndex];
        mFace[faceIndex] = n.matIx()[srcIndex];
        massFace[faceIndex] = n.mass()[srcIndex];
    }
}
