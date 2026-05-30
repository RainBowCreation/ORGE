package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryAssemblerTest {

    private static Material mat(String path, float defaultMass) {
        return new Material(Identifier.fromNamespaceAndPath("orge", path),
                2.5f, 1000f, 0f, defaultMass, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
    }

    @Test
    void uniformSectionGetsOneRealIndexAndItsDefaultMass() {
        Material stone = mat("stone", 2700f);
        MaterialLut lut = new MaterialLut();
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
        MaterialLut lut = new MaterialLut();
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
}
