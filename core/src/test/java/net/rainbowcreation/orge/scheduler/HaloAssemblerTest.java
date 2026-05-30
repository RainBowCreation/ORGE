package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HaloAssemblerTest {

    // same formula as HaloAssembler#sidx (x + 16y + 256z)
    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    private static HaloAssembler.Neighbor identityNeighbor() {
        float[] t = new float[SectionData.CELLS];
        char[] m = new char[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            t[i] = i;
            m[i] = (char) i;
        }
        return new HaloAssembler.Neighbor(t, m, mass);
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
        float[] negXT = h.negXT();
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                assertEquals((float) sidx(15, y, z), negXT[y + 16 * z]);
            }
        }
    }

    @Test
    void posYFacePullsTheNeighboursY0Layer() {
        NeighborHalo h = HaloAssembler.assemble(null, null, null, identityNeighbor(), null, null);
        float[] posYT = h.posYT();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals((float) sidx(x, 0, z), posYT[x + 16 * z]);
            }
        }
    }

    @Test
    void negZFacePullsTheNeighboursZ15LayerForMatIx() {
        NeighborHalo h = HaloAssembler.assemble(null, null, null, null, identityNeighbor(), null);
        char[] negZM = h.negZM();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals((char) sidx(x, y, 15), negZM[x + 16 * y]);
            }
        }
    }

    @Test
    void posXFacePullsTheNeighboursX0Layer() {
        // Only the +X neighbour present (2nd arg).
        NeighborHalo h = HaloAssembler.assemble(null, identityNeighbor(), null, null, null, null);
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                assertEquals((float) sidx(0, y, z), h.posXT()[y + 16 * z]);
            }
        }
    }

    @Test
    void posZFacePullsTheNeighboursZ0Layer() {
        // Only the +Z neighbour present (6th arg).
        NeighborHalo h = HaloAssembler.assemble(null, null, null, null, null, identityNeighbor());
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals((char) sidx(x, y, 0), h.posZM()[x + 16 * y]);
            }
        }
    }

    private static Material mat(String path, float defaultMass) {
        return new Material(Identifier.fromNamespaceAndPath("orge", path),
                2.5f, 1000f, 0f, defaultMass, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
    }

    /** Full-section Neighbor: matIx/mass from GeometryAssembler.assemble + per-cell temps. */
    private static HaloAssembler.Neighbor fullNeighbor(GeometryAssembler.CellMaterials cells,
                                                       float[] temps, MaterialLut lut) {
        GeometryAssembler.Geometry g = GeometryAssembler.assemble(cells, lut);
        return new HaloAssembler.Neighbor(temps, g.matIx(), g.mass());
    }

    /** Face-only Neighbor (the new path): matIx/mass from assembleFace for the contributing plane. */
    private static HaloAssembler.Neighbor faceNeighbor(GeometryAssembler.CellMaterials cells,
                                                       float[] temps, MaterialLut lut,
                                                       GeometryAssembler.Face face) {
        GeometryAssembler.Geometry g = GeometryAssembler.assembleFace(cells, lut, face);
        return new HaloAssembler.Neighbor(temps, g.matIx(), g.mass());
    }

    @Test
    void haloFromFaceOnlyNeighboursIsBitIdenticalToFullSectionNeighbours() {
        Material[] palette = { mat("a", 11f), mat("b", 22f), mat("c", 33f), mat("d", 44f) };
        GeometryAssembler.CellMaterials cells = i -> palette[i % palette.length];
        // Distinct per-cell temps so a mis-mapped face would diverge.
        float[] temps = new float[SectionData.CELLS];
        for (int i = 0; i < temps.length; i++) temps[i] = i * 0.5f;

        // ONE shared LUT for both paths, as in production snapshot() (a full assemble seeds the LUT
        // first, then every neighbour assembles against it), so material indices agree.
        MaterialLut lut = new MaterialLut();
        GeometryAssembler.assemble(cells, lut); // seed the LUT exactly as the owning section does

        NeighborHalo full = HaloAssembler.assemble(
                fullNeighbor(cells, temps, lut), fullNeighbor(cells, temps, lut),
                fullNeighbor(cells, temps, lut), fullNeighbor(cells, temps, lut),
                fullNeighbor(cells, temps, lut), fullNeighbor(cells, temps, lut));
        NeighborHalo face = HaloAssembler.assemble(
                faceNeighbor(cells, temps, lut, GeometryAssembler.Face.NEG_X),
                faceNeighbor(cells, temps, lut, GeometryAssembler.Face.POS_X),
                faceNeighbor(cells, temps, lut, GeometryAssembler.Face.NEG_Y),
                faceNeighbor(cells, temps, lut, GeometryAssembler.Face.POS_Y),
                faceNeighbor(cells, temps, lut, GeometryAssembler.Face.NEG_Z),
                faceNeighbor(cells, temps, lut, GeometryAssembler.Face.POS_Z));

        for (int f = 0; f < 6; f++) {
            assertArrayEquals(full.tempFaces()[f], face.tempFaces()[f], "temp face " + f + " bit-identical");
            assertArrayEquals(full.matFaces()[f], face.matFaces()[f], "mat face " + f + " bit-identical");
            assertArrayEquals(full.massFaces()[f], face.massFaces()[f], "mass face " + f + " bit-identical");
        }
    }
}
