package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryAssemblerTest {

    private static Material mat(String path, float defaultMass) {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", path))
                .thermalConductivity(2.5f).heatCapacity(1000f).molarMass(0f)
                .defaultMass(defaultMass).defaultTemperature(Float.NaN)
                .build();
    }

    static MaterialLut lutOf(net.rainbowcreation.orge.material.Material... reals) {
        java.util.List<net.rainbowcreation.orge.material.Material> ordered = new java.util.ArrayList<>();
        ordered.add(net.rainbowcreation.orge.material.MaterialTable.VACUUM);
        for (var m : reals) ordered.add(m);
        java.util.List<net.rainbowcreation.orge.material.Material> immut = java.util.List.copyOf(ordered);
        return new MaterialLut(immut, net.rainbowcreation.orge.material.MaterialTable.slots(immut));
    }

    @Test
    void uniformSectionGetsOneRealIndexAndItsDefaultMass() {
        Material stone = mat("stone", 2700f);
        MaterialLut lut = lutOf(stone);
        GeometryAssembler.Geometry g = GeometryAssembler.assemble(i -> stone, lut);

        assertEquals(SectionData.CELLS, g.matIx().length);
        assertEquals(SectionData.CELLS, g.mass().length);
        for (int i = 0; i < SectionData.CELLS; i++) {
            assertEquals(1, g.matIx()[i], "all cells map to the single real material at index 1");
            assertEquals(2700f, g.mass()[i]);
        }
    }

    @Test
    void heterogeneousSectionGetsPerCellIndicesAndMasses() {
        Material stone = mat("stone", 2700f);
        Material air = mat("air", 1.2f);
        MaterialLut lut = lutOf(stone, air);
        GeometryAssembler.Geometry g =
                GeometryAssembler.assemble(i -> (i % 2 == 0) ? stone : air, lut);

        assertEquals(2700f, g.mass()[0]);
        assertEquals(1.2f, g.mass()[1]);
        assertEquals(g.matIx()[0], g.matIx()[2], "all stone cells share an index");
        assertNotEquals(g.matIx()[0], g.matIx()[1], "stone and air differ");
        assertEquals(3, lut.materials().size(), "void + stone + air");

        for (int i = 0; i < SectionData.CELLS; i++) {
            if (i % 2 == 0) {
                assertEquals(g.matIx()[0], g.matIx()[i], "even cell is stone");
                assertEquals(2700f, g.mass()[i]);
            } else {
                assertEquals(g.matIx()[1], g.matIx()[i], "odd cell is air");
                assertEquals(1.2f, g.mass()[i]);
            }
        }
    }

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    /** Maps a Face to the (fixed-axis,value) plane the halo extracts, mirroring HaloAssembler. */
    private static boolean inPlane(GeometryAssembler.Face face, int x, int y, int z) {
        return switch (face) {
            case NEG_X -> x == 15;
            case POS_X -> x == 0;
            case NEG_Y -> y == 15;
            case POS_Y -> y == 0;
            case NEG_Z -> z == 15;
            case POS_Z -> z == 0;
        };
    }

    @Test
    void assembleFaceIsBitIdenticalToFullAssembleAtTheFaceCellsForEveryFace() {
        // A heterogeneous section where each cell's material is a deterministic function of its
        // index, so a wrong face/index mapping would show up as a mismatch.
        Material[] palette = {
                mat("a", 10f), mat("b", 20f), mat("c", 30f), mat("d", 40f)
        };
        GeometryAssembler.CellMaterials cells = i -> palette[i % palette.length];

        for (GeometryAssembler.Face face : GeometryAssembler.Face.values()) {
            // SHARED LUT, as in production: snapshot() assembles the section (populating the LUT)
            // and every neighbour face against the same MaterialLut, so indices agree. (Independent
            // LUTs would diverge because assembleFace visits cells in a different order.)
            MaterialLut lut = lutOf(palette);
            GeometryAssembler.Geometry full = GeometryAssembler.assemble(cells, lut);
            GeometryAssembler.Geometry faceGeo = GeometryAssembler.assembleFace(cells, lut, face);

            int facesChecked = 0;
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int i = sidx(x, y, z);
                        if (inPlane(face, x, y, z)) {
                            assertEquals(full.matIx()[i], faceGeo.matIx()[i],
                                    "matIx must match full assemble at face cell " + i + " for " + face);
                            assertEquals(full.mass()[i], faceGeo.mass()[i],
                                    "mass must match full assemble at face cell " + i + " for " + face);
                            facesChecked++;
                        } else {
                            assertEquals(0, faceGeo.matIx()[i],
                                    "off-face cell stays void for " + face + " at " + i);
                            assertEquals(0f, faceGeo.mass()[i],
                                    "off-face cell stays 0 mass for " + face + " at " + i);
                        }
                    }
                }
            }
            assertEquals(256, facesChecked, "exactly 256 cells in the plane for " + face);
        }
    }
}
