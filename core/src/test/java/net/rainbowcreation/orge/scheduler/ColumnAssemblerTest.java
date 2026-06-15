package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.MaterialPalette;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ColumnAssemblerTest {
    private static int colIdx(int x, int sectionY, int sy, int z) {
        return x + 16 * (sectionY * 16 + sy + 64) + 6144 * z;
    }

    private static final Identifier WATER_ID = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STONE_ID = Identifier.fromNamespaceAndPath("minecraft", "stone");

    // LUT/registry: slot 0 vacuum, 1 water (defaultMass 1000), 2 air (1.2), 3 stone (2000).
    private static final List<Material> SLOTS =
            List.of(TestMaterials.voidMat(), TestMaterials.water(), TestMaterials.air(), TestMaterials.stone());
    private static MaterialLut lut() { return TestMaterials.lutOf(SLOTS); }
    private static MaterialRegistry registry() { return TestMaterials.registryOf(SLOTS); }

    @Test
    void airFilledColumnAndFreshFluidSeed() {
        MaterialLut lut = lut();
        MaterialRegistry reg = registry();
        // section reader: section (sy=4) has one freshly-placed water cell (stored mass 0) at (1,2,3);
        // everything else is air (matIx=2). All other sections fully air. No stored material layer.
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

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);
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
        MaterialLut lut = lut();
        MaterialRegistry reg = registry();
        // Two water-labelled cells both at stored mass 0 in section 4:
        //   - cellA (1,2,3): priorSpecies==water (1)  -> engine-DRAINED, MUST NOT reseed (stays 0).
        //   - cellB (5,6,7): priorSpecies==void  (0)  -> genuine NEW placement, MUST seed to 1000.
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

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);

        int ia = colIdx(1, 4, 2, 3);
        assertEquals(1, t.matIx()[ia], "drained cell stays water-labelled");
        assertEquals(0f, t.mass()[ia], 1e-4,
                "engine-drained water (prior==water) is NOT reseeded — no +1000 fabrication");

        int ib = colIdx(5, 4, 6, 7);
        assertEquals(1, t.matIx()[ib], "new-placement cell is water-labelled");
        assertEquals(1000f, t.mass()[ib], 1e-4,
                "genuine new placement (prior==void) IS seeded to defaultMass");
    }

    @Test
    void storedMaterialIsAuthoritative_overridesFirstTouch() {
        // A stable-slot view holding air (1) and water (2): the cell's first-touch is air, but its
        // durable STORED material (water) is authoritative and must resolve to water's fixed slot.
        MaterialLut lut = TestMaterials.lutOf(List.of(TestMaterials.air(), TestMaterials.water()));
        char airIx = lut.indexOf(TestMaterials.air());     // fixed slot 1
        MaterialRegistry reg = registry();
        // The cell's block first-touch is air (matIx=airIx), but its STORED material is orge:water.
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            Identifier[] stored = new Identifier[4096];
            java.util.Arrays.fill(mat, airIx);       // first-touch = air everywhere
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int s = 1 + 16 * 2 + 256 * 3;
                stored[s] = WATER_ID;                // durable stored material = water (authoritative)
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, new char[4096], stored);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);

        char waterIx = lut.indexOf(TestMaterials.water()); // water's fixed slot (2)
        assertNotEquals(airIx, waterIx, "water must have its own slot, not air's");
        int wi = colIdx(1, 4, 2, 3);
        assertEquals(waterIx, t.matIx()[wi],
                "stored water id resolves to water's LUT index, NOT the block's first-touch air index");
        assertEquals(WATER_ID, lut.materials().get(t.matIx()[wi]).id(),
                "the assembled slot is water (its stable slot in the view)");
    }

    @Test
    void storedMaterialNull_fallsBackToFirstTouchMatIx() {
        MaterialLut lut = lut();
        MaterialRegistry reg = registry();
        // storedMaterial all-null ⇒ identity comes from the precomputed first-touch matIx (air=2 here).
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            java.util.Arrays.fill(mat, (char) 2);    // air first-touch
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            return new ColumnAssembler.SectionCells(mat, mass, temp); // storedMaterial all-null
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);
        int ai = colIdx(3, 4, 5, 6);
        assertEquals(2, t.matIx()[ai], "null stored material → first-touch air index");
    }

    @Test
    void storedVacuum_resolvesToIndexZeroSentinel_notFallbackSolid() {
        MaterialLut lut = lut();
        MaterialRegistry reg = registry();   // has generic_solid fallback registered
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            Identifier[] stored = new Identifier[4096];
            java.util.Arrays.fill(mat, (char) 3);    // first-touch stone (decoy)
            java.util.Arrays.fill(mass, 0f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int s = 1 + 16 * 2 + 256 * 3;
                stored[s] = MaterialPalette.VACUUM_ID;   // a broken cell: durable vacuum
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, new char[4096], stored);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);
        int vi = colIdx(1, 4, 2, 3);
        assertEquals(0, t.matIx()[vi],
                "stored orge:vacuum resolves to index 0 (MaterialLut.VACUUM), NOT generic_solid");
        assertEquals(MaterialLut.VACUUM.id(), lut.materials().get(t.matIx()[vi]).id());
        assertEquals(0f, t.mass()[vi], 1e-4, "vacuum defaultMass is 0 — no fabrication");
    }

    @Test
    void seedGate_freshSolidStoneSeedsDefaultMass_butDrainedSameSpeciesDoesNot() {
        // bug-3 prep: the movable() gate is dropped, so a fresh SOLID (stone) at stored mass 0 with a
        // NEW label (prior != mat) now seeds stone.defaultMass(). A drained-but-same-species cell does not.
        MaterialLut lut = lut();
        MaterialRegistry reg = registry();
        Material stone = TestMaterials.stone();
        char stoneIx = lut.indexOf(stone); // 3
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            char[] prior = new char[4096];
            java.util.Arrays.fill(mat, (char) 2);    // air
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int a = 1 + 16 * 2 + 256 * 3;        // fresh stone, prior void -> SEED defaultMass
                mat[a] = stoneIx; mass[a] = 0f; prior[a] = 0;
                int b = 5 + 16 * 6 + 256 * 7;        // drained stone, prior==stone -> NO seed (stays 0)
                mat[b] = stoneIx; mass[b] = 0f; prior[b] = stoneIx;
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, prior);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);

        int ia = colIdx(1, 4, 2, 3);
        assertEquals(stoneIx, t.matIx()[ia]);
        assertEquals(stone.defaultMass(), t.mass()[ia], 1e-4,
                "fresh SOLID (prior!=mat) now seeds defaultMass — movable() gate dropped");

        int ib = colIdx(5, 4, 6, 7);
        assertEquals(stoneIx, t.matIx()[ib]);
        assertEquals(0f, t.mass()[ib], 1e-4,
                "engine-drained same-species solid (prior==mat) is NOT seeded");
    }

    /** S4: a known per-cell enthalpy E [J] in SectionCells lands at the correct engine-order
     *  ColumnTask.enthalpy index — carried section-local→engine-index EXACTLY like temperature. */
    @Test
    void assembleThreadsEnthalpyFromSectionCells() {
        MaterialLut lut = lut();
        MaterialRegistry reg = registry();
        // section 4: cell (1,2,3) carries a distinct enthalpy E; everything else 0.
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            float[] enth = new float[4096];
            java.util.Arrays.fill(mat, (char) 2);     // air
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int s = 1 + 16 * 2 + 256 * 3;
                enth[s] = 4242.5f;                    // distinct E [J] at this cell
            }
            // full ctor with velocity/p/swapReady zero + enthalpy supplied
            return new ColumnAssembler.SectionCells(mat, mass, temp, new char[4096], new Identifier[4096],
                    new float[4096], new float[4096], new float[4096], new float[4096], new float[4096], enth);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);
        int wi = colIdx(1, 4, 2, 3);
        assertEquals(4242.5f, t.enthalpy()[wi], 1e-4,
                "SectionCells enthalpy carried to engine-order ColumnTask.enthalpy index, exact");
        // a neighbouring cell stays 0 (no fabrication / no axis offset bug)
        assertEquals(0f, t.enthalpy()[colIdx(0, 0, 0, 0)], 1e-6);
    }
}
