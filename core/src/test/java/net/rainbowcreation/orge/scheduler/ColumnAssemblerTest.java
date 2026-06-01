package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ColumnAssemblerTest {
    private static int colIdx(int x, int sectionY, int sy, int z) {
        return x + 16 * (sectionY * 16 + sy + 64) + 6144 * z;
    }

    @Test
    void airFilledColumnAndFreshFluidSeed() {
        // LUT: 0 void, 1 water (defaultMass 1000), 2 air (defaultMass 1.2)
        List<Material> lut = List.of(TM.voidMat(), TM.water(), TM.air());
        // section reader: section (cx=0,sy=4) has one freshly-placed water cell (stored mass 0) at (1,2,3);
        // everything else is air (matIx=2). All other sections fully air.
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            java.util.Arrays.fill(mat, (char) 2);     // air
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int s = 1 + 16 * 2 + 256 * 3;
                mat[s] = 1; mass[s] = 0f; temp[s] = 290f; // fresh water, stored mass 0 => must seed to 1000
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, src);
        assertEquals(RegionMarshaller.CHUNK_N, t.matIx().length);
        int wi = colIdx(1, 4, 2, 3);
        assertEquals(1, t.matIx()[wi], "water mapped to engine column index");
        assertEquals(1000f, t.mass()[wi], 1e-4, "fresh fluid (stored<=0) seeded to defaultMass");
        // a neighbouring air cell stays ambient
        int ai = colIdx(0, 0, 0, 0);
        assertEquals(2, t.matIx()[ai]);
        assertEquals(1.2f, t.mass()[ai], 1e-4);
    }

    // Minimal local material factory mirroring Section11LivePipelineReproTest constructor calls.
    static final class TM {
        static Material voidMat() { return net.rainbowcreation.orge.engine.TestMaterials.voidMat(); }
        static Material water()   { return net.rainbowcreation.orge.engine.TestMaterials.water(); }
        static Material air()     { return net.rainbowcreation.orge.engine.TestMaterials.air(); }
    }
}
