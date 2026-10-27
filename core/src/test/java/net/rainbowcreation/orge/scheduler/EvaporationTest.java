package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static net.rainbowcreation.orge.scheduler.SkyTestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Evaporation} (issues #34, #80): by day and dry, the sky pass evaporates the water — free water or pore
 * water — of each column whose surface is the top of the world into the free atmosphere, at the #28
 * exchange's rate (faster in a hot biome, via the water's own T), never underground, as a booked boundary
 * sink. Every surface inside the domain is the engine's vapor exchange's (issue #80): the pass never touches
 * it. Grand + per-species mass and grand energy are exact including the ledgers.
 */
class EvaporationTest {

    private static final Identifier STEAM = orge("steam");
    private static final SubchunkKey SURFACE_SECTION = new SubchunkKey(0, 4, 0);
    private static final SubchunkKey TOP_SECTION = new SubchunkKey(0, ColumnAssembler.MAX_SECTION_Y, 0);
    /** The surface layer cell (local y 8) of the flat world. */
    private static final int LAYER = cell(3, 8, 5);
    /** The air cell directly above it. */
    private static final int ABOVE = cell(3, 9, 5);
    /** A world-top surface cell (local y 15 of the top section). */
    private static final int TOP = cell(3, 15, 5);

    /** A desert (biome base 2.0) and a taiga (0.25) ambient. */
    private static final float HOT_K = BiomeTemperature.toKelvin(2.0f);
    private static final float COLD_K = BiomeTemperature.toKelvin(0.25f);

    private static SurfaceForcing.Climate overworld(long dayTime, float rainLevel) {
        return SurfaceForcing.Climate.forDimension(OVERWORLD, dayTime, rainLevel).orElseThrow();
    }

    private static final SurfaceForcing.Climate NOON = overworld(6000L, 0f);

    private static void pass(Evaporation e, SectionStore store, SurfaceForcing.Climate c, double dt) {
        SkyColumnPass.walkAndPut(store, 0, 0, lookup(), e.visitor(OVERWORLD, c, dt, lookup()));
    }

    /** Mass [kg] of {@code species} in cell {@code i} across its slots. */
    private static float massOf(SectionData d, int i, Identifier species) {
        float w = species.equals(d.materialAt(i)) ? d.massAt(i) : 0f;
        for (int k = 1; k < SectionData.SLOTS; k++) {
            if (species.equals(d.slotMaterialAt(k, i))) w += d.slotMassAt(k, i);
        }
        return w;
    }

    /** Exact Σ mass [kg] of {@code species} over every stored section of column (0,0), all slots. */
    private static BigDecimal grandMass(SectionStore store, Identifier species) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int sy = ColumnAssembler.MIN_SECTION_Y; sy <= ColumnAssembler.MAX_SECTION_Y; sy++) {
            SubchunkKey key = new SubchunkKey(0, sy, 0);
            if (!store.hasSection(key)) continue;
            SectionData d = store.get(key);
            for (int i = 0; i < SectionData.CELLS; i++) {
                if (species.equals(d.materialAt(i))) sum = sum.add(new BigDecimal(d.massAt(i)));
                for (int k = 1; k < SectionData.SLOTS; k++) {
                    if (species.equals(d.slotMaterialAt(k, i))) sum = sum.add(new BigDecimal(d.slotMassAt(k, i)));
                }
            }
        }
        return sum;
    }

    /** E [J] of a composition at one T: {@code Σ m_k·h_k(T)}. */
    private static double eOf(SectionData d, int i, float tempK) {
        double e = 0.0;
        for (int k = 0; k < SectionData.SLOTS; k++) {
            Identifier id = k == 0 ? d.materialAt(i) : d.slotMaterialAt(k, i);
            float m = k == 0 ? d.massAt(i) : d.slotMassAt(k, i);
            Material mat = lookup().apply(id);
            if (mat != null && m > 0f) e += (double) m * EnthalpyCurve.hOf(mat, lookup(), tempK);
        }
        return e;
    }

    /** Flat world whose surface layer is a {@code mass}-kg puddle of water, everything at {@code tempK}. */
    private static SectionStore puddleWorld(Path dir, float mass, float tempK) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, WATER, tempK);
        SectionData surface = store.get(SURFACE_SECTION);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) setCell(surface, cell(x, 8, z), mat(WATER), mass, tempK);
        return store;
    }

    /** A world whose surface is the top of the world: the top section stone under a {@code mass}-kg water layer. */
    private static SectionStore topPuddleWorld(Path dir, float mass, float tempK) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        SectionData top = putSection(store, ColumnAssembler.MAX_SECTION_Y, sy -> STONE, tempK);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) setCell(top, cell(x, 15, z), mat(WATER), mass, tempK);
        return store;
    }

    /** A whole Minecraft day (24000 ticks) of one-second sky passes, forcing and evaporation together. */
    private static void simulatedDay(Evaporation e, SectionStore store) {
        SurfaceForcing forcing = new SurfaceForcing();
        for (int s = 0; s < 1200; s++) {
            SurfaceForcing.Climate c = overworld(20L * s, 0f);
            SkyColumnPass.walkAndPut(store, 0, 0, lookup(),
                    forcing.visitor(OVERWORLD, c, 1.0, lookup()).andThen(e.visitor(OVERWORLD, c, 1.0, lookup())));
        }
    }

    @Test
    void aHotBiomePuddleShrinksFasterOverASimulatedDayThanAColdOne(@TempDir Path dir) {
        SectionStore hot = topPuddleWorld(dir.resolve("hot"), 400f, HOT_K);
        SectionStore cold = topPuddleWorld(dir.resolve("cold"), 400f, COLD_K);
        Evaporation e = new Evaporation();

        simulatedDay(e, hot);
        simulatedDay(new Evaporation(), cold);

        float hotLoss = 400f - hot.get(TOP_SECTION).massAt(TOP);
        float coldLoss = 400f - cold.get(TOP_SECTION).massAt(TOP);
        assertTrue(coldLoss > 0f, "even the taiga puddle evaporates by day: " + coldLoss);
        assertTrue(hotLoss > 3f * coldLoss, "the desert puddle loses far more: " + hotLoss + " vs " + coldLoss);
        assertEquals(-e.evaporated(OVERWORLD), e.mass(OVERWORLD), 0.0, "every kilogram left as a booked sink");
        assertEquals(WATER, hot.get(TOP_SECTION).materialAt(TOP), "the puddle is still water");
    }

    @Test
    void nothingEvaporatesAtNightWhileItRainsOrInTheEnd(@TempDir Path dir) {
        List<SurfaceForcing.Climate> skies = List.of(
                overworld(18000L, 0f),                                                   // a clear night
                overworld(0L, 0f),                                                       // sunrise: no sun yet
                overworld(6000L, 1f),                                                    // raining noon
                overworld(6000L, 0.3f),                                                  // light rain, sun through
                SurfaceForcing.Climate.forDimension(THE_END, 6000L, 0f).orElseThrow());  // no sun
        int n = 0;
        for (SurfaceForcing.Climate c : skies) {
            SectionStore store = topPuddleWorld(dir.resolve("s" + n++), 1000f, HOT_K);
            Evaporation e = new Evaporation();
            for (int s = 0; s < 30; s++) pass(e, store, c, 1.0);
            assertEquals(1000f, store.get(TOP_SECTION).massAt(TOP), 0f, "no evaporation under " + c);
            assertEquals(0.0, e.evaporated(OVERWORLD), "nothing booked under " + c);
            assertEquals(0.0, e.mass(OVERWORLD), "nothing booked under " + c);
        }
    }

    @Test
    void poreWaterOfASandSurfaceEvaporatesAndTheSandIsUntouched(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        SectionData top = putSection(store, ColumnAssembler.MAX_SECTION_Y, sy -> SAND, HOT_K);
        top.setSlot(1, TOP, WATER, 150f);
        top.setEnthalpy(TOP, eOf(top, TOP, HOT_K));
        float sand = top.massAt(TOP);

        Evaporation e = new Evaporation();
        for (int s = 0; s < 60; s++) pass(e, store, NOON, 1.0);

        assertTrue(massOf(top, TOP, WATER) < 150f, "pore water evaporated: " + massOf(top, TOP, WATER));
        assertEquals(150.0 - massOf(top, TOP, WATER), e.evaporated(OVERWORLD), 0.0, "booked as it left");
        assertEquals(sand, top.massAt(TOP), 0f, "the sand skeleton keeps its mass");
        assertEquals(SAND, top.materialAt(TOP));
    }

    @Test
    void undergroundWaterIsNeverTouched(@TempDir Path dir) {
        SectionStore store = topPuddleWorld(dir, 1000f, HOT_K);
        SectionData top = store.get(TOP_SECTION);
        setCell(top, TOP, mat(STONE), 2700f, HOT_K);              // a stone cap over (3, 5) ...
        int pool = cell(3, 14, 5);
        setCell(top, pool, mat(WATER), 1000f, HOT_K);             // ... over a pool: a cave pool
        int below = cell(4, 14, 5);                               // stone with pore water under the surface of (4, 5)
        top.setSlot(1, below, WATER, 100f);
        top.setEnthalpy(below, eOf(top, below, HOT_K));

        Evaporation e = new Evaporation();
        for (int s = 0; s < 60; s++) pass(e, store, NOON, 1.0);

        assertEquals(1000f, top.massAt(pool), 0f, "the capped pool does not evaporate");
        assertEquals(100f, massOf(top, below, WATER), 0f, "water below the surface cell is untouched");
        assertTrue(top.massAt(cell(4, 15, 5)) < 1000f, "while the open neighbour evaporates");
    }

    /** Issue #80: the engine's vapor exchange owns every face inside the domain, so the pass leaves it alone. */
    @Test
    void theEngineOwnsEverySurfaceFaceInsideTheDomain(@TempDir Path dir) {
        SectionStore flat = puddleWorld(dir.resolve("flat"), 1000f, HOT_K);       // air above the surface
        SectionStore edge = loaded(dir.resolve("edge"), OVERWORLD).store(OVERWORLD);
        putSection(edge, 4, sy -> sy < 15 ? STONE : WATER, HOT_K);               // the section above is unstored
        Evaporation e = new Evaporation();

        for (int s = 0; s < 60; s++) {
            pass(e, flat, NOON, 1.0);
            pass(e, edge, NOON, 1.0);
        }

        assertEquals(1000f, flat.get(SURFACE_SECTION).massAt(LAYER), 0f, "a surface under air is the engine's");
        assertEquals(0f, massOf(flat.get(SURFACE_SECTION), ABOVE, STEAM), 0f, "no vapor from the sky pass");
        assertEquals(0, grandMass(flat, STEAM).signum(), "anywhere");
        assertEquals(mat(WATER).defaultMass(), edge.get(SURFACE_SECTION).massAt(cell(3, 15, 5)), 0f,
                "a surface under an unstored section is inside the world: the engine's too");
        assertEquals(0.0, e.evaporated(OVERWORLD), "nothing evaporated");
        assertEquals(0.0, e.mass(OVERWORLD), "nothing booked");
        assertEquals(0.0, e.energy(OVERWORLD), "nothing booked");
    }

    @Test
    void theDonorPaysTheLatentHeatTheVaporCarries(@TempDir Path dir) {
        SectionStore store = topPuddleWorld(dir, 1000f, 300f);
        SectionData d = store.get(TOP_SECTION);
        double ea0 = d.enthalpyAt(TOP);

        Evaporation e = new Evaporation();
        pass(e, store, NOON, 1.0);

        double x = 1000.0 - d.massAt(TOP);
        assertTrue(x > 0, "NON-VACUOUS: water evaporated");
        double hSteam = EnthalpyCurve.hOf(mat(STEAM), lookup(), 300f);
        double hWater = EnthalpyCurve.hOf(mat(WATER), lookup(), 300f);
        assertTrue(hSteam - hWater > 2.2e6, "the vapor's enthalpy includes the latent heat");
        assertEquals(-x * hSteam, d.enthalpyAt(TOP) - ea0, 1e-6 * x * hSteam, "the donor pays m·h_steam(T)");
        assertTrue(tempOf(d, TOP) < 300f, "evaporation cools the water: " + tempOf(d, TOP));
    }

    @Test
    void theRateIsPenmansExchangeIntoTheFreeAtmosphere(@TempDir Path dir) {
        Material water = mat(WATER);
        Material steam = mat(STEAM);
        assertEquals(101325.0, Evaporation.pSat(water, steam, 373.0), 1e-6, "p_sat(T_b) = P_atm exactly");
        assertEquals(2.46e3, Evaporation.pSat(water, steam, 293.15), 0.03e3, "the engine's 293 K value");
        assertEquals(3.73e3, Evaporation.pSat(water, steam, 300.0), 0.05e3, "IAPWS 3.54 kPa +5 %");

        SectionStore store = topPuddleWorld(dir, 200f, 300f);
        pass(new Evaporation(), store, NOON, 1.0);

        double f = Evaporation.G_VAP_PENMAN * Evaporation.pSat(water, steam, 300.0);   // χ_pair·w_liq ≈ 1
        double x = 200.0 - store.get(TOP_SECTION).massAt(TOP);
        assertEquals(f, x, Math.ulp(200f), "one 1 s pass moves g_vap·p_sat(T) to within a lattice step");
    }

    @Test
    void aSurfaceAtTheWorldTopLosesItsVaporToTheLedger(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        SectionData top = putSection(store, ColumnAssembler.MAX_SECTION_Y, sy -> AIR, 300f);
        int cell = cell(2, 15, 2);
        setCell(top, cell, mat(WATER), 500f, 300f);
        double e0 = top.enthalpyAt(cell);

        Evaporation e = new Evaporation();
        for (int s = 0; s < 20; s++) pass(e, store, NOON, 1.0);

        double lost = 500.0 - top.massAt(cell);
        assertTrue(lost > 0, "NON-VACUOUS: the top water evaporated out of the world");
        assertEquals(-lost, e.mass(OVERWORLD), 0.0, "every kilogram is a booked boundary sink");
        assertEquals(top.enthalpyAt(cell) - e0, e.energy(OVERWORLD), 1e-9 * Math.abs(e0),
                "with its enthalpy");
        assertTrue(e.energy(OVERWORLD) < -lost * 2.2e6, "the vapor left with its latent heat");
    }

    @Test
    void anEvaporatedWorldTopPuddleSurvivesASaveAndReload(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir, OVERWORLD);
        SectionStore store = mgr.store(OVERWORLD);
        putSection(store, ColumnAssembler.MAX_SECTION_Y, sy -> sy < 15 ? STONE : WATER, 300f);
        mgr.onLevelSave(OVERWORLD);                                   // clean before the pass

        for (int s = 0; s < 5; s++) pass(new Evaporation(), store, NOON, 1.0);
        float water = store.get(TOP_SECTION).massAt(TOP);
        assertTrue(water < mat(WATER).defaultMass(), "the top water evaporated");
        mgr.onLevelSave(OVERWORLD);
        mgr.onLevelUnload(OVERWORLD);

        SectionStoreManager reloaded = loaded(dir, OVERWORLD);
        assertEquals(water, reloaded.store(OVERWORLD).get(TOP_SECTION).massAt(TOP), 0f,
                "the donor's section is marked dirty, so the next save keeps the loss");
    }

    @Test
    void grandAndPerSpeciesMassAndGrandEnergyAreExactWithTheLedgers(@TempDir Path dir) {
        SectionStore store = puddleWorld(dir, 700f, HOT_K);
        SectionData top = putSection(store, ColumnAssembler.MAX_SECTION_Y, sy -> AIR, HOT_K);
        for (int x = 0; x < 16; x++) {                       // one row of world-top wet sand ...
            int i = cell(x, 15, 0);
            setCell(top, i, mat(SAND), 1600f, HOT_K);
            top.setSlot(1, i, WATER, 120f + x);
            top.setEnthalpy(i, eOf(top, i, HOT_K));
        }
        setCell(top, cell(0, 15, 1), mat(WATER), 300f, HOT_K);   // ... and one world-top puddle: the sinks
        BigDecimal h2o0 = grandMass(store, WATER).add(grandMass(store, STEAM));
        BigDecimal sand0 = grandMass(store, SAND);
        BigDecimal stone0 = grandMass(store, STONE);
        BigDecimal air0 = grandMass(store, AIR);
        BigDecimal e0 = grandE(store);
        SurfaceForcing forcing = new SurfaceForcing();
        Evaporation e = new Evaporation();

        for (int s = 0; s < 300; s++) {
            SurfaceForcing.Climate c = overworld(3000L + 20L * s, 0f);
            SkyColumnPass.walkAndPut(store, 0, 0, lookup(),
                    forcing.visitor(OVERWORLD, c, 1.0, lookup()).andThen(e.visitor(OVERWORLD, c, 1.0, lookup())));
        }

        assertTrue(e.evaporated(OVERWORLD) > 0, "NON-VACUOUS: water evaporated");
        BigDecimal h2oChange = grandMass(store, WATER).add(grandMass(store, STEAM)).subtract(h2o0);
        assertEquals(0, h2oChange.compareTo(new BigDecimal(e.mass(OVERWORLD))),
                "Σ (water + steam) change " + h2oChange + " = the sink ledger " + e.mass(OVERWORLD) + " exactly");
        assertEquals(0, grandMass(store, STEAM).signum(), "the vapor all left the world");
        assertEquals(700f, store.get(SURFACE_SECTION).massAt(LAYER), 0f, "the in-domain puddles are the engine's");
        assertEquals(0, sand0.compareTo(grandMass(store, SAND)), "sand mass unchanged");
        assertEquals(0, stone0.compareTo(grandMass(store, STONE)), "stone mass unchanged");
        assertEquals(0, air0.compareTo(grandMass(store, AIR)), "air mass unchanged");
        BigDecimal eChange = grandE(store).subtract(e0);
        BigDecimal ledgers = new BigDecimal(forcing.ledger(OVERWORLD)).add(new BigDecimal(e.energy(OVERWORLD)));
        assertEquals(0, eChange.compareTo(ledgers),
                "Σ E change " + eChange + " = forcing + evaporation energy ledgers " + ledgers + " exactly");
    }
}
