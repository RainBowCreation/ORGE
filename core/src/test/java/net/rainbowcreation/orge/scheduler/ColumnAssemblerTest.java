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

    @Test
    void signatureGate_drainedWaterNotReseeded_butNewPlacementIs() {
        // LUT: 0 void, 1 water, 2 air. Two water-labelled cells both at stored mass 0 in section 4:
        //   - cellA (1,2,3): priorSpecies==water (1)  -> engine-DRAINED, MUST NOT reseed (stays 0).
        //   - cellB (5,6,7): priorSpecies==void  (0)  -> genuine NEW placement, MUST seed to 1000.
        // This is the mass-fabrication gate: the storedMass<=0 test alone cannot separate these.
        List<Material> lut = List.of(TM.voidMat(), TM.water(), TM.air());
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            char[] prior = new char[4096];           // default void (0) everywhere
            java.util.Arrays.fill(mat, (char) 2);    // air
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int a = 1 + 16 * 2 + 256 * 3;        // drained-but-still-water cell
                mat[a] = 1; mass[a] = 0f; prior[a] = 1;   // prior == water == current => NO reseed
                int b = 5 + 16 * 6 + 256 * 7;        // genuinely new water placement
                mat[b] = 1; mass[b] = 0f; prior[b] = 0;   // prior == void != water => SEED to 1000
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, prior);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, src);

        int ia = colIdx(1, 4, 2, 3);
        assertEquals(1, t.matIx()[ia], "drained cell stays water-labelled");
        assertEquals(0f, t.mass()[ia], 1e-4,
                "engine-drained water (prior==water) is NOT reseeded — no +1000 fabrication");

        int ib = colIdx(5, 4, 6, 7);
        assertEquals(1, t.matIx()[ib], "new-placement cell is water-labelled");
        assertEquals(1000f, t.mass()[ib], 1e-4,
                "genuine new placement (prior==void) IS seeded to defaultMass");
    }

    // Minimal local material factory mirroring Section11LivePipelineReproTest constructor calls.
    static final class TM {
        static Material voidMat() { return net.rainbowcreation.orge.engine.TestMaterials.voidMat(); }
        static Material water()   { return net.rainbowcreation.orge.engine.TestMaterials.water(); }
        static Material air()     { return net.rainbowcreation.orge.engine.TestMaterials.air(); }
    }
}
