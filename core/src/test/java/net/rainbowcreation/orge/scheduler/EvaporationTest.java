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
 * {@link Evaporation} (issue #34): by day and dry, the sky pass evaporates each column's surface water —
 * free water or pore water — into the air cell above as vapor, at the #28 exchange's rate (faster in a hot
 * biome, via the water's own T), never underground, with grand + per-species mass and grand energy exact
 * including the ledgers.
 */
class EvaporationTest {

    private static final Identifier STEAM = orge("steam");
    private static final SubchunkKey SURFACE_SECTION = new SubchunkKey(0, 4, 0);
    /** The surface layer cell (local y 8) of the flat world. */
    private static final int LAYER = cell(3, 8, 5);
    /** The air cell directly above it. */
    private static final int ABOVE = cell(3, 9, 5);

    /** A desert (biome base 2.0) and a taiga (0.25) ambient. */
    private static final float HOT_K = BiomeTemperature.toKelvin(2.0f);
    private static final float COLD_K = BiomeTemperature.toKelvin(0.25f);

    private static SurfaceForcing.Climate overworld(long dayTime, float rainLevel) {
        return SurfaceForcing.Climate.forDimension(OVERWORLD, dayTime, rainLevel).orElseThrow();
    }

    private static final SurfaceForcing.Climate NOON = overworld(6000L, 0f);

    private static void pass(Evaporation e, SectionStore store, SurfaceForcing.Climate c, double dt) {
        SkyColumnPass.walkAndPut(store, 0, 0, lookup(), e.visitor(OVERWORLD, c, dt, store, lookup()));
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

    /** A whole Minecraft day (24000 ticks) of one-second sky passes, forcing and evaporation together. */
    private static void simulatedDay(Evaporation e, SectionStore store) {
        SurfaceForcing forcing = new SurfaceForcing();
        for (int s = 0; s < 1200; s++) {
            SurfaceForcing.Climate c = overworld(20L * s, 0f);
            SkyColumnPass.walkAndPut(store, 0, 0, lookup(),
                    forcing.visitor(OVERWORLD, c, 1.0, lookup()).andThen(e.visitor(OVERWORLD, c, 1.0, store, lookup())));
        }
    }

    @Test
    void aHotBiomePuddleShrinksFasterOverASimulatedDayThanAColdOne(@TempDir Path dir) {
        SectionStore hot = puddleWorld(dir.resolve("hot"), 400f, HOT_K);
        SectionStore cold = puddleWorld(dir.resolve("cold"), 400f, COLD_K);

        simulatedDay(new Evaporation(), hot);
        simulatedDay(new Evaporation(), cold);

        float hotLoss = 400f - hot.get(SURFACE_SECTION).massAt(LAYER);
        float coldLoss = 400f - cold.get(SURFACE_SECTION).massAt(LAYER);
        assertTrue(coldLoss > 0f, "even the taiga puddle evaporates by day: " + coldLoss);
        assertTrue(hotLoss > 3f * coldLoss, "the desert puddle loses far more: " + hotLoss + " vs " + coldLoss);
        assertEquals(hotLoss, massOf(hot.get(SURFACE_SECTION), ABOVE, STEAM), 0f,
                "the water went into the air above as vapor, kilogram for kilogram");
        assertEquals(WATER, hot.get(SURFACE_SECTION).materialAt(LAYER), "the puddle is still water");
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
            SectionStore store = puddleWorld(dir.resolve("s" + n++), 1000f, HOT_K);
            Evaporation e = new Evaporation();
            for (int s = 0; s < 30; s++) pass(e, store, c, 1.0);
            assertEquals(1000f, store.get(SURFACE_SECTION).massAt(LAYER), 0f, "no evaporation under " + c);
            assertEquals(0f, massOf(store.get(SURFACE_SECTION), ABOVE, STEAM), 0f);
            assertEquals(0.0, e.evaporated(OVERWORLD), "nothing booked under " + c);
        }
    }

    @Test
    void poreWaterOfASandSurfaceEvaporatesAndTheSandIsUntouched(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, SAND, HOT_K);
        SectionData surface = store.get(SURFACE_SECTION);
        surface.setSlot(1, LAYER, WATER, 150f);
        surface.setEnthalpy(LAYER, eOf(surface, LAYER, HOT_K));
        float sand = surface.massAt(LAYER);

        Evaporation e = new Evaporation();
        for (int s = 0; s < 60; s++) pass(e, store, NOON, 1.0);

        assertTrue(massOf(surface, LAYER, WATER) < 150f, "pore water evaporated: " + massOf(surface, LAYER, WATER));
        assertTrue(massOf(surface, ABOVE, STEAM) > 0f, "into the air above");
        assertEquals(sand, surface.massAt(LAYER), 0f, "the sand skeleton keeps its mass");
        assertEquals(SAND, surface.materialAt(LAYER));
        assertEquals(0f, massOf(surface, cell(4, 9, 5), STEAM), 0f, "a dry sand column gives no vapor");
    }

    @Test
    void undergroundWaterIsNeverTouched(@TempDir Path dir) {
        SectionStore store = puddleWorld(dir, 1000f, HOT_K);
        SectionData roof = putSection(store, 6, sy -> AIR, HOT_K);
        setCell(roof, cell(3, 0, 5), mat(STONE), 2700f, HOT_K);   // a one-block roof over (3, 5): a cave pool
        SectionData surface = store.get(SURFACE_SECTION);
        int below = cell(4, 7, 5);                                // water under the surface cell of (4, 5)
        surface.setSlot(1, below, WATER, 100f);
        surface.setEnthalpy(below, eOf(surface, below, HOT_K));

        Evaporation e = new Evaporation();
        for (int s = 0; s < 60; s++) pass(e, store, NOON, 1.0);

        assertEquals(1000f, surface.massAt(LAYER), 0f, "the roofed pool does not evaporate");
        assertEquals(0f, massOf(surface, ABOVE, STEAM), 0f, "and its air holds no vapor");
        assertEquals(0f, massOf(roof, cell(3, 1, 5), STEAM), 0f, "the stone roof has no water to give");
        assertEquals(100f, massOf(surface, below, WATER), 0f, "water below the surface cell is untouched");
        assertTrue(surface.massAt(cell(4, 8, 5)) < 1000f, "while the open neighbour evaporates");
    }

    @Test
    void theDonorPaysTheLatentHeatTheVaporCarries(@TempDir Path dir) {
        SectionStore store = puddleWorld(dir, 1000f, 300f);
        SectionData d = store.get(SURFACE_SECTION);
        double ea0 = d.enthalpyAt(LAYER);
        double eb0 = d.enthalpyAt(ABOVE);

        pass(new Evaporation(), store, NOON, 1.0);

        double x = 1000.0 - d.massAt(LAYER);
        assertTrue(x > 0, "NON-VACUOUS: water evaporated");
        double hSteam = EnthalpyCurve.hOf(mat(STEAM), lookup(), 300f);
        double hWater = EnthalpyCurve.hOf(mat(WATER), lookup(), 300f);
        assertTrue(hSteam - hWater > 2.2e6, "the vapor's enthalpy includes the latent heat");
        assertEquals(-x * hSteam, d.enthalpyAt(LAYER) - ea0, 1e-6 * x * hSteam, "the donor pays m·h_steam(T)");
        assertEquals(x * hSteam, d.enthalpyAt(ABOVE) - eb0, 1e-6 * x * hSteam, "the receiver gains it");
        assertTrue(tempOf(d, LAYER) < 300f, "evaporation cools the water: " + tempOf(d, LAYER));
    }

    @Test
    void theRateIsPenmansExchangeIntoDryAir(@TempDir Path dir) {
        Material water = mat(WATER);
        Material steam = mat(STEAM);
        assertEquals(101325.0, Evaporation.pSat(water, steam, 373.0), 1e-6, "p_sat(T_b) = P_atm exactly");
        assertEquals(2.46e3, Evaporation.pSat(water, steam, 293.15), 0.03e3, "the engine's 293 K value");
        assertEquals(3.73e3, Evaporation.pSat(water, steam, 300.0), 0.05e3, "IAPWS 3.54 kPa +5 %");

        SectionStore store = puddleWorld(dir, 200f, 300f);
        pass(new Evaporation(), store, NOON, 1.0);

        double f = Evaporation.G_VAP_PENMAN * Evaporation.pSat(water, steam, 300.0);   // χ_pair·w_liq·w_gas ≈ 1
        double x = 200.0 - store.get(SURFACE_SECTION).massAt(LAYER);
        assertEquals(f, x, Math.ulp(200f), "one 1 s pass moves g_vap·p_sat(T) to within a lattice step");
    }

    @Test
    void saturatedAirTakesNoMoreVapor(@TempDir Path dir) {
        SectionStore store = puddleWorld(dir, 1000f, 300f);
        SectionData d = store.get(SURFACE_SECTION);
        Material steam = mat(STEAM);
        double vSat = Evaporation.pSat(mat(WATER), steam, 300.0) * 1.0 * steam.molarMass() / (8.314 * 300.0);
        d.setSlot(1, ABOVE, STEAM, (float) (1.01 * vSat));       // air above already past saturation
        d.setEnthalpy(ABOVE, eOf(d, ABOVE, 300f));

        pass(new Evaporation(), store, NOON, 1.0);

        assertEquals(1000f, d.massAt(LAYER), 0f, "no drive into saturated air");
        assertTrue(d.massAt(cell(4, 8, 5)) < 1000f, "while the dry neighbour evaporates");
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
    void vaporIntoTheSectionAboveSurvivesASaveAndReload(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir, OVERWORLD);
        SectionStore store = mgr.store(OVERWORLD);
        putSection(store, 4, sy -> sy < 15 ? STONE : WATER, 300f);   // the surface is the section's top cell
        putSection(store, 5, sy -> AIR, 300f);
        mgr.onLevelSave(OVERWORLD);                                   // clean before the pass

        for (int s = 0; s < 5; s++) pass(new Evaporation(), store, NOON, 1.0);
        int above = cell(3, 0, 5);
        float vapor = massOf(store.get(new SubchunkKey(0, 5, 0)), above, STEAM);
        assertTrue(vapor > 0f, "the vapor crossed into the section above");
        mgr.onLevelSave(OVERWORLD);
        mgr.onLevelUnload(OVERWORLD);

        SectionStoreManager reloaded = loaded(dir, OVERWORLD);
        assertEquals(vapor, massOf(reloaded.store(OVERWORLD).get(new SubchunkKey(0, 5, 0)), above, STEAM), 0f,
                "the receiver's section is marked dirty too, so the next save keeps the vapor");
    }

    @Test
    void grandAndPerSpeciesMassAndGrandEnergyAreExactWithTheLedgers(@TempDir Path dir) {
        SectionStore store = puddleWorld(dir, 700f, HOT_K);
        SectionData surface = store.get(SURFACE_SECTION);
        for (int x = 0; x < 16; x++) {                       // one row of wet sand among the puddles
            int i = cell(x, 8, 0);
            setCell(surface, i, mat(SAND), 1600f, HOT_K);
            surface.setSlot(1, i, WATER, 120f + x);
            surface.setEnthalpy(i, eOf(surface, i, HOT_K));
        }
        SectionData top = putSection(store, ColumnAssembler.MAX_SECTION_Y, sy -> AIR, HOT_K);
        setCell(top, cell(0, 15, 0), mat(WATER), 300f, HOT_K);   // one column's surface is the world top: a sink
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
                    forcing.visitor(OVERWORLD, c, 1.0, lookup()).andThen(e.visitor(OVERWORLD, c, 1.0, store, lookup())));
        }

        assertTrue(e.evaporated(OVERWORLD) > 0, "NON-VACUOUS: water evaporated");
        assertTrue(e.mass(OVERWORLD) < 0, "NON-VACUOUS: some left the world");
        BigDecimal h2oChange = grandMass(store, WATER).add(grandMass(store, STEAM)).subtract(h2o0);
        assertEquals(0, h2oChange.compareTo(new BigDecimal(e.mass(OVERWORLD))),
                "Σ (water + steam) change " + h2oChange + " = the sink ledger " + e.mass(OVERWORLD) + " exactly");
        assertTrue(grandMass(store, STEAM).signum() > 0, "the rest is vapor in the air");
        assertEquals(0, sand0.compareTo(grandMass(store, SAND)), "sand mass unchanged");
        assertEquals(0, stone0.compareTo(grandMass(store, STONE)), "stone mass unchanged");
        assertEquals(0, air0.compareTo(grandMass(store, AIR)), "air mass unchanged");
        BigDecimal eChange = grandE(store).subtract(e0);
        BigDecimal ledgers = new BigDecimal(forcing.ledger(OVERWORLD)).add(new BigDecimal(e.energy(OVERWORLD)));
        assertEquals(0, eChange.compareTo(ledgers),
                "Σ E change " + eChange + " = forcing + evaporation energy ledgers " + ledgers + " exactly");
    }
}
