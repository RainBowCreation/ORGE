package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeighborHaloTest {

    private static float[] tf(float v) { float[] a = new float[NeighborHalo.FACE_CELLS]; java.util.Arrays.fill(a, v); return a; }
    private static char[]  mf(char v)  { char[]  a = new char[NeighborHalo.FACE_CELLS]; java.util.Arrays.fill(a, v); return a; }

    @Test
    void faceCellsIs256() {
        assertEquals(256, NeighborHalo.FACE_CELLS);
    }

    @Test
    void orderedAccessorsFollowNegPosXYZ() {
        NeighborHalo h = new NeighborHalo(
                tf(1), tf(2), tf(3), tf(4), tf(5), tf(6),
                mf((char) 1), mf((char) 2), mf((char) 3), mf((char) 4), mf((char) 5), mf((char) 6));

        float[][] temps = h.tempFaces();
        char[][]  mats  = h.matFaces();

        assertEquals(6, temps.length);
        assertEquals(6, mats.length);
        // Order must be negX, posX, negY, posY, negZ, posZ.
        for (int f = 0; f < 6; f++) {
            assertEquals((float) (f + 1), temps[f][0], "temp face " + f);
            assertEquals((char) (f + 1), mats[f][0], "mat face " + f);
        }
    }
}
