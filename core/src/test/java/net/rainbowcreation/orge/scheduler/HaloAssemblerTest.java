package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HaloAssemblerTest {

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    private static HaloAssembler.Neighbor identityNeighbor() {
        float[] t = new float[SectionData.CELLS];
        char[] m = new char[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            t[i] = i;
            m[i] = (char) i;
        }
        return new HaloAssembler.Neighbor(t, m);
    }

    @Test
    void nullNeighboursProduceVoidFaces() {
        NeighborHalo h = HaloAssembler.assemble(null, null, null, null, null, null);
        for (float[] face : h.tempFaces()) {
            assertEquals(NeighborHalo.FACE_CELLS, face.length);
            for (float v : face) assertEquals(0f, v);
        }
        for (char[] face : h.matFaces()) {
            for (char v : face) assertEquals(0, v, "absent neighbour is void (index 0)");
        }
    }

    @Test
    void negXFacePullsTheNeighboursX15Layer() {
        NeighborHalo h = HaloAssembler.assemble(identityNeighbor(), null, null, null, null, null);
        float[] negXT = h.tempFaces()[0];
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                assertEquals((float) sidx(15, y, z), negXT[y + 16 * z]);
            }
        }
    }

    @Test
    void posYFacePullsTheNeighboursY0Layer() {
        NeighborHalo h = HaloAssembler.assemble(null, null, null, identityNeighbor(), null, null);
        float[] posYT = h.tempFaces()[3];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals((float) sidx(x, 0, z), posYT[x + 16 * z]);
            }
        }
    }

    @Test
    void negZFacePullsTheNeighboursZ15LayerForMatIx() {
        NeighborHalo h = HaloAssembler.assemble(null, null, null, null, identityNeighbor(), null);
        char[] negZM = h.matFaces()[4];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals((char) sidx(x, y, 15), negZM[x + 16 * y]);
            }
        }
    }
}
