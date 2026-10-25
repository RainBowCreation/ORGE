package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static net.rainbowcreation.orge.scheduler.SkyTestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SurfaceForcing} (issue #32): Overworld day/night, rain, the End's permanent sink and the
 * unforced Nether, with the boundary ledger equal to the stored energy change exactly.
 */
class SurfaceForcingTest {

    private static final int SURFACE = cell(3, 7, 5); // top stone cell of section 4 in the flat world
    private static final SubchunkKey SURFACE_SECTION = new SubchunkKey(0, 4, 0);

    /** {@code seconds} one-second passes starting at {@code dayTime}, advancing 20 ticks per second. */
    private static void run(SurfaceForcing f, SectionStore store, long dayTime, int seconds, float rain) {
        for (int s = 0; s < seconds; s++) {
            SurfaceForcing.Climate c = SurfaceForcing.Climate
                    .forDimension(OVERWORLD, dayTime + 20L * s, rain).orElseThrow();
            f.applyColumn(OVERWORLD, store, 0, 0, c, 1.0, lookup());
        }
    }

    private static void assertLedgerExact(BigDecimal before, SectionStore store, double ledger) {
        BigDecimal change = grandE(store).subtract(before);
        assertEquals(0, change.compareTo(new BigDecimal(ledger)),
                "Σ E change " + change + " must equal the ledger " + ledger + " exactly");
    }

    @Test
    void overworldSurfaceWarmsOverTheDayAndCoolsOverTheNight(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        SectionData surface = store.get(SURFACE_SECTION);
        SurfaceForcing f = new SurfaceForcing();
        BigDecimal start = grandE(store);
        float e0 = surface.enthalpyAt(SURFACE);

        run(f, store, 0L, 600, 0f);                         // sunrise → sunset (12000 ticks)
        float eDusk = surface.enthalpyAt(SURFACE);
        assertTrue(eDusk > e0, "E rises over a clear day: " + e0 + " → " + eDusk);
        assertTrue(tempOf(surface, SURFACE) > 285f, "and the derived T with it");
        assertTrue(f.ledger(OVERWORLD) > 0, "the day books net energy in");
        assertLedgerExact(start, store, f.ledger(OVERWORLD));

        BigDecimal dusk = grandE(store);
        double ledgerDusk = f.ledger(OVERWORLD);
        run(f, store, 12000L, 600, 0f);                     // sunset → sunrise
        float eDawn = surface.enthalpyAt(SURFACE);
        assertTrue(eDawn < eDusk, "E falls over the night: " + eDusk + " → " + eDawn);
        assertTrue(f.ledger(OVERWORLD) < ledgerDusk, "the night books energy out to the sky");
        assertLedgerExact(dusk, store, f.ledger(OVERWORLD) - ledgerDusk);
        assertLedgerExact(start, store, f.ledger(OVERWORLD));
    }

    @Test
    void airAboveTheSurfaceIsNeverForced(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        SectionData air = store.get(new SubchunkKey(0, 5, 0));
        float before = air.enthalpyAt(cell(3, 0, 5));
        run(new SurfaceForcing(), store, 6000L, 10, 0f);
        assertEquals(before, air.enthalpyAt(cell(3, 0, 5)), 0f, "transparent air takes no sun");
    }

    @Test
    void rainBlocksTheSunSoARainyNoonCools(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        SectionData surface = store.get(SURFACE_SECTION);
        SurfaceForcing f = new SurfaceForcing();
        BigDecimal start = grandE(store);
        float e0 = surface.enthalpyAt(SURFACE);
        run(f, store, 5000L, 100, 1f);                      // full rain around noon
        assertTrue(surface.enthalpyAt(SURFACE) < e0, "no sun through rain: the sky loss wins");
        assertLedgerExact(start, store, f.ledger(OVERWORLD));
    }

    @Test
    void theEndSurfaceLosesEnergyEverySecond(@TempDir Path dir) {
        SectionStore store = loaded(dir, THE_END).store(THE_END);
        flatWorld(store, null, 285f);
        SectionData surface = store.get(SURFACE_SECTION);
        SurfaceForcing f = new SurfaceForcing();
        BigDecimal start = grandE(store);
        float prev = surface.enthalpyAt(SURFACE);
        for (long tick = 0; tick < 2 * SurfaceForcing.DAY_TICKS; tick += 400) {   // two days, any hour
            SurfaceForcing.Climate c = SurfaceForcing.Climate.forDimension(THE_END, tick, 0f).orElseThrow();
            assertEquals(0.0, c.solar(), "no sun in the End");
            f.applyColumn(THE_END, store, 0, 0, c, 20.0, lookup());
            float e = surface.enthalpyAt(SURFACE);
            assertTrue(e < prev, "monotonic loss at tick " + tick + ": " + prev + " → " + e);
            prev = e;
        }
        assertTrue(f.ledger(THE_END) < 0, "the End only ever books energy out");
        assertLedgerExact(start, store, f.ledger(THE_END));
    }

    @Test
    void theSinkNeverCoolsACellBelowItsSky(@TempDir Path dir) {
        SectionStore store = loaded(dir, THE_END).store(THE_END);
        flatWorld(store, null, 285f);
        SectionData surface = store.get(SURFACE_SECTION);
        SurfaceForcing f = new SurfaceForcing();
        SurfaceForcing.Climate end = SurfaceForcing.Climate.forDimension(THE_END, 0L, 0f).orElseThrow();
        // One absurdly long pass: the explicit σT⁴ loss would overshoot far below 0 K; the floor stops it at T_sky.
        f.applyColumn(THE_END, store, 0, 0, end, 1e9, lookup());
        assertEquals(SurfaceForcing.END_SKY_K, tempOf(surface, SURFACE), 0.05);
        f.applyColumn(THE_END, store, 0, 0, end, 1e9, lookup());
        assertEquals(SurfaceForcing.END_SKY_K, tempOf(surface, SURFACE), 0.05, "and it rests there");
    }

    @Test
    void theNetherHasNoSky() {
        assertTrue(SurfaceForcing.Climate.forDimension(THE_NETHER, 6000L, 0f).isEmpty());
        assertEquals(0.0, new SurfaceForcing().ledger(THE_NETHER));
    }

    @Test
    void theOverworldSunIsTheRealDailyMean() {
        // ∫ over one day of S(t) = 161 W/m², the TFK 2009 surface-absorbed mean; zero all night; peak π·161.
        double sum = 0;
        for (long t = 0; t < SurfaceForcing.DAY_TICKS; t++) {
            sum += SurfaceForcing.Climate.forDimension(OVERWORLD, t, 0f).orElseThrow().solar();
        }
        assertEquals(SurfaceForcing.SOLAR_ABSORBED_MEAN, sum / SurfaceForcing.DAY_TICKS, 1e-6);
        assertEquals(Math.PI * 161.0, SurfaceForcing.Climate.forDimension(OVERWORLD, 6000L, 0f).orElseThrow().solar(), 1e-9);
        assertEquals(0.0, SurfaceForcing.Climate.forDimension(OVERWORLD, 18000L, 0f).orElseThrow().solar());
        assertEquals(SurfaceForcing.OVERWORLD_SKY_K, Math.pow(333.0 / SurfaceForcing.SIGMA, 0.25), 0.1);
        assertEquals(Math.PI * 161.0 * 0.5,
                SurfaceForcing.Climate.forDimension(OVERWORLD, 6000L, 0.5f).orElseThrow().solar(), 1e-9);
    }

    @Test
    void aMixedSurfaceCellIsForcedOnItsMixtureTemperature(@TempDir Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        SectionData surface = store.get(SURFACE_SECTION);
        // Wet soil at the surface: dirt + pore water, one E at 285 K for the whole cell.
        surface.setMaterialAt(SURFACE, DIRT);
        surface.setMass(SURFACE, 1500f);
        surface.setSlot(1, SURFACE, WATER, 200f);
        double e285 = net.rainbowcreation.orge.material.EnthalpyCurve.cellE(1500f, mat(DIRT), lookup(), 285f)
                + net.rainbowcreation.orge.material.EnthalpyCurve.cellE(200f, mat(WATER), lookup(), 285f);
        surface.setEnthalpy(SURFACE, (float) e285);
        float before = surface.enthalpyAt(SURFACE);
        SurfaceForcing f = new SurfaceForcing();
        BigDecimal start = grandE(store);

        SurfaceForcing.Climate noon = SurfaceForcing.Climate.forDimension(OVERWORLD, 6000L, 0f).orElseThrow();
        f.applyColumn(OVERWORLD, store, 0, 0, noon, 1.0, lookup());
        double w = SkyColumnPass.cellEmissivity(surface, SURFACE, lookup());
        double loss = SurfaceForcing.SIGMA * (Math.pow(285.0, 4) - Math.pow(SurfaceForcing.OVERWORLD_SKY_K, 4));
        double expected = w * (noon.solar() - loss);
        assertEquals(expected, surface.enthalpyAt(SURFACE) - before, 64.0,
                "the mixture's own T (285 K) sets the emission");
        assertLedgerExact(start, store, f.ledger(OVERWORLD));
    }

    @Test
    void forcedEnergySurvivesASaveAndReload(@TempDir Path dir) {
        net.rainbowcreation.orge.section.SectionStoreManager mgr = loaded(dir, OVERWORLD);
        flatWorld(mgr.store(OVERWORLD), null, 285f);
        mgr.onLevelSave(OVERWORLD);                         // clean: nothing dirty before the pass
        run(new SurfaceForcing(), mgr.store(OVERWORLD), 6000L, 10, 0f);
        float forced = mgr.store(OVERWORLD).get(SURFACE_SECTION).enthalpyAt(SURFACE);
        mgr.onLevelSave(OVERWORLD);
        mgr.onLevelUnload(OVERWORLD);

        net.rainbowcreation.orge.section.SectionStoreManager reloaded = loaded(dir, OVERWORLD);
        assertEquals(forced, reloaded.store(OVERWORLD).get(SURFACE_SECTION).enthalpyAt(SURFACE), 0f,
                "the pass marks its sections dirty, so the next save keeps the forced E");
    }
}
