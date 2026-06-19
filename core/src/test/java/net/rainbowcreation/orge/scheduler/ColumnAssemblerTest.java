package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
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
        assertEquals(0, t.matIx()[ia],
                "engine-drained-empty water (prior==water, mass 0) becomes orge:vacuum — fillable, not a water@0 ghost");
        assertEquals(0f, t.mass()[ia], 1e-4,
                "drained-to-empty cell holds no mass (no +1000 fabrication; relabelled vacuum)");

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
        assertEquals(0, t.matIx()[ib],
                "engine-drained-empty same-species cell (prior==mat, mass 0) becomes orge:vacuum, not a species@0 ghost");
        assertEquals(0f, t.mass()[ib], 1e-4,
                "drained-to-empty cell holds no mass (no fabrication; relabelled vacuum)");
    }

    /** The break bug (2026-06-19): a STONE cell is broken → its block becomes minecraft:air →
     *  first-touch orge:air, stored mass 0, prior == stone (the old solid). It MUST assemble as
     *  orge:vacuum (a fillable empty cell), NOT a mass-0 orge:air ghost (orge:air's min_mass=1.0
     *  cohesion floor would refuse lava/water inflow). Ambient air (rest mass) is untouched. */
    @Test
    void brokenSolidCellBecomesVacuumNotAirGhost() {
        MaterialLut lut = lut();
        MaterialRegistry reg = registry();
        final char airIx = 2, stoneIx = 3;
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            char[] prior = new char[4096];
            java.util.Arrays.fill(mat, airIx);       // ambient air everywhere
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int s = 1 + 16 * 2 + 256 * 3;
                mat[s] = airIx; mass[s] = 0f; prior[s] = stoneIx; // broken: first-touch air, was stone, now empty
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, prior);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);

        int bi = colIdx(1, 4, 2, 3);
        assertEquals(0, t.matIx()[bi],
                "broken solid cell (now empty) assembles as orge:vacuum, NOT an orge:air@0 ghost that blocks inflow");
        assertEquals(0f, t.mass()[bi], 1e-4, "no air fabricated into the broken cell");
        // ambient air (rest mass) is unaffected
        int ai = colIdx(0, 0, 0, 0);
        assertEquals(airIx, t.matIx()[ai], "ambient air stays air");
        assertEquals(1.2f, t.mass()[ai], 1e-4, "ambient air keeps its rest mass");
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
                "SectionCells enthalpy carried to engine-order ColumnTask.enthalpy index, exact (nonzero "
                        + "stored E is authoritative, passed through unchanged)");
        // A neighbouring air cell has NO stored E (incoming 0): law §6/§7 the assembler now ENCODES
        // E = m·h(T_seed) from its seeded mass + seed temp (else it would derive T = 0 K). It is the
        // distinct cell's E that must be carried verbatim, not a global all-zero enthalpy field.
        java.util.function.Function<Identifier, Material> lookup = id -> reg.get(id).orElse(null);
        float airE = (float) EnthalpyCurve.cellE(1.2f, TestMaterials.air(), lookup, 300f);
        assertEquals(airE, t.enthalpy()[colIdx(0, 0, 0, 0)], 1e-2,
                "seed/ambient air cell (stored E == 0) encodes E = m·h(T_seed), not 0");
    }

    /** T2-regression guard (law §6/§7): a seed/ambient cell with NO stored E (enthalpy 0) must have its
     *  E ENCODED ONCE from its seeded mass + seed temperature — NOT passed through as 0 (which would
     *  derive T = 0 K downstream). A cell carrying an authoritative nonzero E is passed through UNCHANGED
     *  (never re-encoded — that would re-introduce the cp·T lossiness Task 2 removed). */
    @Test
    void seedCellWithoutStoredE_encodesEFromMassAndSeedTemp_authoritativeEPassesThrough() {
        MaterialLut lut = lut();
        MaterialRegistry reg = registry();
        Material water = TestMaterials.water();
        java.util.function.Function<Identifier, Material> lookup = id -> reg.get(id).orElse(null);

        // cellA (1,2,3): a SEED water cell — incoming enthalpy 0, mass 1000 kg, temp 285 K.
        //   Expect E ENCODED = cellE(1000, water, lookup, 285), strictly > 0.
        // cellB (5,6,7): an AUTHORITATIVE water cell — incoming enthalpy 7777.0 (nonzero), passed through.
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            char[] prior = new char[4096];          // void (0) -> seed gate engages for new water labels
            float[] enth = new float[4096];
            java.util.Arrays.fill(mat, (char) 2);   // air
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int a = 1 + 16 * 2 + 256 * 3;       // seed water (mass already 1000, enthalpy 0)
                mat[a] = 1; mass[a] = 1000f; temp[a] = 285f; enth[a] = 0f;
                int b = 5 + 16 * 6 + 256 * 7;       // authoritative water (nonzero stored E)
                mat[b] = 1; mass[b] = 1000f; temp[b] = 285f; enth[b] = 7777.0f;
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, prior, new Identifier[4096],
                    new float[4096], new float[4096], new float[4096], new float[4096], new float[4096], enth);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, reg, src);

        int ia = colIdx(1, 4, 2, 3);
        float expectE = (float) EnthalpyCurve.cellE(1000f, water, lookup, 285f);
        assertTrue(expectE > 0f, "water at 285 K has positive E (sanity)");
        assertEquals(expectE, t.enthalpy()[ia], 1e-3,
                "seed cell (stored E == 0) encodes E = m·h(T_seed), NOT 0 (else T derives to 0 K)");
        assertTrue(t.enthalpy()[ia] > 0f, "seed E must be strictly positive");

        int ib = colIdx(5, 4, 6, 7);
        assertEquals(7777.0f, t.enthalpy()[ib], 1e-4,
                "authoritative nonzero stored E is passed through UNCHANGED (not re-encoded)");
    }
}
