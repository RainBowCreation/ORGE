package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
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
 * {@link RainSeeding} (issue #33): rain seeds water into the sky pass's exposed cells — pore water in
 * sand / dirt, a free-water component on stone, the pool's own slot on water — never under a roof, with
 * grand + per-species mass and grand energy exact including the boundary ledgers.
 */
class RainSeedingTest {

    private static final SubchunkKey SURFACE_SECTION = new SubchunkKey(0, 4, 0);
    /** The surface layer cell (local y 8) of the flat world when it has a surface material. */
    private static final int LAYER = cell(3, 8, 5);
    /** The top stone cell (local y 7) of the flat world with no surface layer. */
    private static final int TOP_STONE = cell(3, 7, 5);

    /** A raining Overworld night (no sun, so only the rain acts on the cell). */
    private static SurfaceForcing.Climate rainyNight(float rainLevel) {
        return SurfaceForcing.Climate.forDimension(OVERWORLD, 18000L, rainLevel).orElseThrow();
    }

    /** {@code passes} rain-only sky passes of {@code dt} seconds over column (0,0). */
    private static void rain(RainSeeding r, SectionStore store, SurfaceForcing.Climate c, double dt, int passes) {
        for (int n = 0; n < passes; n++) {
            SkyColumnPass.walkAndPut(store, 0, 0, lookup(), r.visitor(OVERWORLD, c, dt, lookup()));
        }
    }

    /** Water [kg] held by cell {@code i} across its slots. */
    private static float waterIn(SectionData d, int i) {
        float w = WATER.equals(d.materialAt(i)) ? d.massAt(i) : 0f;
        for (int k = 1; k < SectionData.SLOTS; k++) {
            if (WATER.equals(d.slotMaterialAt(k, i))) w += d.slotMassAt(k, i);
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

    /** One pass's rain on an open {@code (x, z)} column at full rain: {@code I·dt·Σw}, with {@code Σw = 1 − τ_end}
     *  within {@link SkyColumnPass#TAU_MIN} of 1. */
    private static double seedPerPass(double dt) {
        return RainSeeding.RAIN_INTENSITY * dt;
    }

    @Test
    void rainOverSandAndDirtSoaksThePoreWater(@TempDir Path dir) {
        for (Identifier soil : List.of(SAND, DIRT)) {
            SectionStore store = loaded(dir.resolve(soil.getPath()), OVERWORLD).store(OVERWORLD);
            flatWorld(store, soil, 285f);
            SectionData surface = store.get(SURFACE_SECTION);
            float soilMass = surface.massAt(LAYER);
            assertTrue(mat(soil).flowResistance() > 0f, soil + " is a skeleton: water in it is pore water");

            rain(new RainSeeding(), store, rainyNight(1f), 1.0, 60);

            assertEquals(60 * seedPerPass(1.0), waterIn(surface, LAYER), 1e-4 * 60 * seedPerPass(1.0),
                    soil + " soaks the column's rain: 60 s × I");
            assertEquals(soil, surface.materialAt(LAYER), "the skeleton stays the cell's material");
            assertEquals(soilMass, surface.massAt(LAYER), 0f, "and its mass is untouched");
            assertEquals(0f, waterIn(surface, cell(3, 7, 5)), 0f, "the rain lands on the surface, not below it");
        }
    }

    @Test
    void rainOverStoneFormsAFreeWaterComponent(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        SectionData surface = store.get(SURFACE_SECTION);
        assertEquals(0f, mat(STONE).flowResistance(), 0f, "stone has no skeleton pores");

        rain(new RainSeeding(), store, rainyNight(1f), 1.0, 10);

        assertEquals(STONE, surface.materialAt(TOP_STONE));
        assertEquals(2700f, surface.massAt(TOP_STONE), 0f, "the stone is untouched");
        assertEquals(WATER, surface.slotMaterialAt(1, TOP_STONE), "water joins the composition as its own slot");
        assertTrue(surface.slotMassAt(1, TOP_STONE) > 0f, "a free-water component: " + surface.slotMassAt(1, TOP_STONE));
    }

    @Test
    void rainOverWaterJoinsThePool(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, WATER, 285f);
        SectionData surface = store.get(SURFACE_SECTION);

        rain(new RainSeeding(), store, rainyNight(1f), 1.0, 10);

        assertEquals(1000.0 + 10 * seedPerPass(1.0), surface.massAt(LAYER), 1e-3,
                "the pool's own water slot grows");
        assertFalse(surface.hasSlots(), "no second water component is opened");
    }

    @Test
    void aRoofedColumnAndTheAirReceiveNothing(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        SectionData roof = putSection(store, 6, sy -> AIR, 285f);
        int roofCell = cell(3, 0, 5);
        setCell(roof, roofCell, mat(STONE), 2700f, 285f);   // a one-block roof over (3, 5)
        SectionData surface = store.get(SURFACE_SECTION);
        SectionData air = store.get(new SubchunkKey(0, 5, 0));
        BigDecimal airBefore = grandMass(store, AIR);

        rain(new RainSeeding(), store, rainyNight(1f), 1.0, 10);

        assertEquals(0f, waterIn(surface, TOP_STONE), 0f, "the covered surface stays dry under a one-block roof");
        assertEquals(10 * seedPerPass(1.0), waterIn(roof, roofCell), 1e-4 * 10 * seedPerPass(1.0),
                "the roof takes all of its column's rain");
        assertTrue(waterIn(surface, cell(4, 7, 5)) > 0f, "the open neighbour column is rained on");
        for (int i = 0; i < SectionData.CELLS; i++) {
            assertEquals(0f, waterIn(air, i), 0f, "transparent air takes no rain (cell " + i + ")");
        }
        assertEquals(0, airBefore.compareTo(grandMass(store, AIR)), "and its mass is untouched");
    }

    @Test
    void noRainSeedsNothing(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, SAND, 285f);
        RainSeeding r = new RainSeeding();
        rain(r, store, rainyNight(0f), 1.0, 10);
        assertEquals(0f, waterIn(store.get(SURFACE_SECTION), LAYER), 0f);
        assertEquals(0.0, r.mass(OVERWORLD));
        assertEquals(0.0, r.energy(OVERWORLD));
        assertFalse(store.get(SURFACE_SECTION).hasSlots(), "no empty water slot is opened");
    }

    @Test
    void aFullCompositionHasNoRoomForWater(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        SectionData surface = store.get(SURFACE_SECTION);
        surface.setSlot(1, TOP_STONE, DIRT, 1f);
        surface.setSlot(2, TOP_STONE, SAND, 1f);
        surface.setSlot(3, TOP_STONE, OAK_LEAVES, 1f);
        float massBefore = surface.cellMassAt(TOP_STONE);
        double eBefore = surface.enthalpyAt(TOP_STONE);

        rain(new RainSeeding(), store, rainyNight(1f), 1.0, 10);

        assertEquals(0f, waterIn(surface, TOP_STONE), 0f, "four foreign slots: the seed is clamped to 0");
        assertEquals(massBefore, surface.cellMassAt(TOP_STONE), 0f);
        assertEquals(eBefore, surface.enthalpyAt(TOP_STONE), 0.0, "nothing entered, no energy either");
    }

    @Test
    void anEmptyFirstSlotTakesTheWater(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        SectionData surface = store.get(SURFACE_SECTION);
        surface.setMass(TOP_STONE, 0f);                      // the stone lives in slot 1; slot 0 is empty
        surface.setSlot(1, TOP_STONE, STONE, 2700f);

        rain(new RainSeeding(), store, rainyNight(1f), 1.0, 10);

        assertEquals(WATER, surface.materialAt(TOP_STONE));
        assertEquals(10 * seedPerPass(1.0), surface.massAt(TOP_STONE), 1e-4 * 10 * seedPerPass(1.0));
        assertEquals(2700f, surface.slotMassAt(1, TOP_STONE), 0f, "the stone keeps its slot");
    }

    @Test
    void aMaterialTableWithoutWaterMakesNoRain(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        java.util.function.Function<Identifier, net.rainbowcreation.orge.material.Material> noWater =
                id -> WATER.equals(id) ? null : lookup().apply(id);
        RainSeeding r = new RainSeeding();
        SkyColumnPass.walkAndPut(store, 0, 0, noWater, r.visitor(OVERWORLD, rainyNight(1f), 1.0, noWater));
        assertEquals(0.0, r.mass(OVERWORLD));
        assertFalse(store.get(SURFACE_SECTION).hasSlots());
    }

    @Test
    void rainEntersAtItsRealTemperature(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, WATER, (float) RainSeeding.RAIN_TEMP_K);
        SectionData surface = store.get(SURFACE_SECTION);
        float m0 = surface.massAt(LAYER);
        double e0 = surface.enthalpyAt(LAYER);

        rain(new RainSeeding(), store, rainyNight(1f), 1.0, 30);

        double hRain = EnthalpyCurve.hOf(mat(WATER), lookup(), (float) RainSeeding.RAIN_TEMP_K);
        assertEquals(((double) surface.massAt(LAYER) - m0) * hRain, surface.enthalpyAt(LAYER) - e0, 1e-4,
                "each kg brings h_water(T_rain) (to the double ULPs of a ~1e9 J cell over 30 adds)");
        assertEquals(RainSeeding.RAIN_TEMP_K, tempOf(surface, LAYER), 1e-3,
                "a pool already at the rain's temperature stays there");
    }

    @Test
    void grandAndPerSpeciesMassAndGrandEnergyAreExactWithTheLedgers(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, SAND, 285f);
        // Water already in a few surface cells, so seeds land both in new and in existing slots.
        SectionData surface = store.get(SURFACE_SECTION);
        surface.setSlot(1, cell(0, 8, 0), WATER, 50f);
        surface.setSlot(2, cell(1, 8, 0), WATER, 0.25f);
        BigDecimal water0 = grandMass(store, WATER);
        BigDecimal sand0 = grandMass(store, SAND);
        BigDecimal stone0 = grandMass(store, STONE);
        BigDecimal air0 = grandMass(store, AIR);
        BigDecimal e0 = grandE(store);
        SurfaceForcing forcing = new SurfaceForcing();
        RainSeeding r = new RainSeeding();

        for (int s = 0; s < 50; s++) {                     // a half-rain noon: the sun and the rain both act
            SurfaceForcing.Climate c = SurfaceForcing.Climate.forDimension(OVERWORLD, 6000L + 20L * s, 0.5f)
                    .orElseThrow();
            SkyColumnPass.walkAndPut(store, 0, 0, lookup(),
                    forcing.visitor(OVERWORLD, c, 1.0, lookup()).andThen(r.visitor(OVERWORLD, c, 1.0, lookup())));
        }

        assertTrue(r.mass(OVERWORLD) > 0, "NON-VACUOUS: rain fell");
        BigDecimal waterGain = grandMass(store, WATER).subtract(water0);
        assertEquals(0, waterGain.compareTo(new BigDecimal(r.mass(OVERWORLD))),
                "Σ water change " + waterGain + " = the rain mass ledger " + r.mass(OVERWORLD) + " exactly");
        assertEquals(0, sand0.compareTo(grandMass(store, SAND)), "sand mass unchanged");
        assertEquals(0, stone0.compareTo(grandMass(store, STONE)), "stone mass unchanged");
        assertEquals(0, air0.compareTo(grandMass(store, AIR)), "air mass unchanged");
        BigDecimal eChange = grandE(store).subtract(e0);
        BigDecimal ledgers = new BigDecimal(forcing.ledger(OVERWORLD)).add(new BigDecimal(r.energy(OVERWORLD)));
        assertEquals(0, eChange.compareTo(ledgers),
                "Σ E change " + eChange + " = forcing + rain energy ledgers " + ledgers + " exactly");
        assertTrue(r.energy(OVERWORLD) > 0, "the rain's enthalpy is booked");
    }

    @Test
    void seededWaterSurvivesASaveAndReload(@TempDir Path dir) {
        SectionStoreManager mgr = loaded(dir, OVERWORLD);
        flatWorld(mgr.store(OVERWORLD), null, 285f);
        mgr.onLevelSave(OVERWORLD);                         // clean: nothing dirty before the pass
        rain(new RainSeeding(), mgr.store(OVERWORLD), rainyNight(1f), 1.0, 5);
        float seeded = waterIn(mgr.store(OVERWORLD).get(SURFACE_SECTION), TOP_STONE);
        assertTrue(seeded > 0f);
        mgr.onLevelSave(OVERWORLD);
        mgr.onLevelUnload(OVERWORLD);

        SectionStoreManager reloaded = loaded(dir, OVERWORLD);
        assertEquals(seeded, waterIn(reloaded.store(OVERWORLD).get(SURFACE_SECTION), TOP_STONE), 0f,
                "the pass marks its sections dirty, so the next save keeps the water");
    }
}
