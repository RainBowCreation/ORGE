package net.rainbowcreation.orge;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.CellMaterialTracker;
import net.rainbowcreation.orge.scheduler.CrossSectionSeamPass;
import net.rainbowcreation.orge.scheduler.MaterialChangeReseed;
import net.rainbowcreation.orge.scheduler.Scheduler;
import net.rainbowcreation.orge.scheduler.StepValidator;
import net.rainbowcreation.orge.scheduler.ThermalWorld;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.RegionStore;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Native-backed end-to-end regression for the cross-section vertical fluid fall (DESIGN §10
 * Phase-2b / cross-section fall, slice-1). Tasks 1–3 proved the pure logic
 * ({@link net.rainbowcreation.orge.scheduler.CrossSectionFluidLogic}), the seam-pass orchestration
 * ({@link CrossSectionSeamPass}) and the Scheduler wiring in isolation with hand-built planes. THIS
 * test closes the loop: it drives the REAL native engine (per-section advection against a read-only
 * halo, so fluid only ever piles on each section's y=0 floor) AND the REAL
 * {@link CrossSectionSeamPass} (which then bleeds that floor plane down into the section below),
 * cycle after cycle, against a real {@link SectionStore} + {@link CellMaterialTracker} — proving
 * water actually CASCADES DOWN across a section seam through production code, conserving mass every
 * cycle, with no residue and without re-triggering the reseed-misfire.
 *
 * <p>SKIPS (never fails) when the bundled {@code liborge.so} is absent, mirroring
 * {@link AuditScenarioTest}'s native scenarios.</p>
 */
class CrossSectionCascadeNativeTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");
    private static final Identifier ICE = Identifier.fromNamespaceAndPath("minecraft", "ice");

    private static final int SEC_N = SectionData.CELLS;          // 4096
    private static final int FACE = NeighborHalo.FACE_CELLS;     // 256

    // LUT species indices.
    private static final char VOID_IX  = 0;
    private static final char WATER_IX = 1;
    private static final char AIR_IX   = 2;

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    // --- Materials (mirror AuditScenarioTest helpers) -------------------------------------------
    // water: fluid, defaultMass 1000, floor 125, cap 1000.
    private static Material water() {
        return new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                373.15f, 273.15f, STEAM, ICE, null,
                Float.NaN, false, Material.State.FLUID, 125f, 1000f);
    }
    // air: State.AIR, air()==true, fluid()==false, resting ~1.2 kg, at a NON-ZERO LUT index.
    private static Material air() {
        return new Material(AIR, 0.026f, 1005f, 0f, 1.2f, 0.029f,
                Float.POSITIVE_INFINITY, 0f, null, null, null,
                Float.NaN, false, Material.State.AIR, 0f, 0f);
    }
    private static Material voidMat() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "void"),
                0f, 0f, 0f, 0f, 0.018f, 9999f, 0f, null, null, null);
    }

    /** LUT: 0=VOID, 1=WATER, 2=AIR. */
    private static List<Material> lut() {
        return List.of(voidMat(), water(), air());
    }

    private static NeighborHalo voidHalo() {
        float[] zf = new float[FACE];
        char[]  zc = new char[FACE];
        return new NeighborHalo(
                zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(),
                zc.clone(), zc.clone(), zc.clone(), zc.clone(), zc.clone(), zc.clone(),
                zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone());
    }

    private static final AmbientProvider AMB = new AmbientProvider() {
        @Override public float ambientTemperatureK(SubchunkKey k) { return 285f; }
        @Override public float ambientMassKg(SubchunkKey k) { return 1.2f; }
    };

    /** Capturing waker collecting woken keys. */
    private static final class CapturingWaker implements CrossSectionSeamPass.FlowWaker {
        final List<SubchunkKey> waked = new ArrayList<>();
        @Override public void wake(Identifier dim, SubchunkKey key) { waked.add(key); }
    }

    /** char species -> Identifier[] (index 0 reuses orge:void id). Mirrors the live recordCellMaterials. */
    private static Identifier[] idsFrom(char[] sp, List<Material> lut) {
        Identifier[] ids = new Identifier[sp.length];
        for (int i = 0; i < sp.length; i++) ids[i] = lut.get(sp[i]).id();
        return ids;
    }

    /** species char[] for a section, from the tracker's recorded prior ids, via the id->ix map. */
    private static char[] speciesFromTracker(Identifier[] prior, List<Material> lut) {
        char[] sp = new char[SEC_N];
        for (int i = 0; i < SEC_N; i++) {
            Identifier id = prior[i];
            char ix = 0;
            for (int s = 0; s < lut.size(); s++) {
                if (lut.get(s).id().equals(id)) { ix = (char) s; break; }
            }
            sp[i] = ix;
        }
        return sp;
    }

    /** Total fluid()+air() mass across one section's store data (the headline conservation gate). */
    private static double fluidAirMass(SectionData data, char[] species, List<Material> lut) {
        double s = 0;
        for (int i = 0; i < SEC_N; i++) {
            Material m = lut.get(species[i]);
            if (m.fluid() || m.air()) s += data.massAt(i);
        }
        return s;
    }

    @Test
    void waterCascadesDownAcrossASectionSeamConservingMassAndReseedSafe(@TempDir Path world) {
        NativeEngine e;
        try {
            net.rainbowcreation.orge.engine.OrgeEngine eng = EngineFactory.create();
            assumeTrue(eng instanceof NativeEngine,
                    "no bundled liborge for this platform; got " + eng.getClass().getSimpleName());
            e = (NativeEngine) eng;
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
            return;
        }

        final List<Material> lut = lut();
        final Material water = water();
        assertTrue(water.fluid(), "audit water must be a fluid to advect");
        assertTrue(air().air() && !air().fluid(), "air must be air() and not fluid()");

        final SubchunkKey upper = new SubchunkKey(0, 1, 0);
        final SubchunkKey lower = new SubchunkKey(0, 0, 0);

        // --- Seed UPPER: a block of WATER near the FLOOR (y 0..3, x 6..9, z 6..9), everything else
        //     REAL AIR (so the engine has air to fall through / swap with, mirroring in-game ambient).
        char[]  upMat  = new char[SEC_N];
        float[] upMass = new float[SEC_N];
        float[] upTemp = new float[SEC_N];
        Arrays.fill(upMat, AIR_IX);
        Arrays.fill(upMass, 1.2f);
        Arrays.fill(upTemp, 290f);
        int waterSeedCells = 0;
        for (int y = 0; y <= 3; y++)
            for (int x = 6; x <= 9; x++)
                for (int z = 6; z <= 9; z++) {
                    int i = sidx(x, y, z);
                    upMat[i] = WATER_IX; upMass[i] = 1000f; upTemp[i] = 290f;
                    waterSeedCells++;
                }

        // --- Seed LOWER: ALL REAL AIR (species air, 1.2 kg).
        char[]  loMat  = new char[SEC_N];
        float[] loMass = new float[SEC_N];
        float[] loTemp = new float[SEC_N];
        Arrays.fill(loMat, AIR_IX);
        Arrays.fill(loMass, 1.2f);
        Arrays.fill(loTemp, 285f);

        // --- Put both sections into a real store, record initial species in the tracker.
        SectionStore store = new SectionStore(new RegionStore(world), AMB);
        CellMaterialTracker tracker = new CellMaterialTracker();
        store.put(upper, SectionData.full(upTemp.clone(), upMass.clone()));
        store.put(lower, SectionData.full(loTemp.clone(), loMass.clone()));
        tracker.record(DIM, upper, idsFrom(upMat, lut));
        tracker.record(DIM, lower, idsFrom(loMat, lut));

        CapturingWaker waker = new CapturingWaker();

        // Headline conservation invariant: total fluid+air mass across BOTH sections is invariant.
        double initialTotal =
                fluidAirMass(store.get(upper), speciesFromTracker(tracker.prior(DIM, upper), lut), lut)
              + fluidAirMass(store.get(lower), speciesFromTracker(tracker.prior(DIM, lower), lut), lut);
        // (upper: 64 water cells * 1000 + remaining air * 1.2) + (lower: all air * 1.2)
        double convTol = StepValidator.MASS_EPSILON_PER_CELL * (2.0 * SEC_N);

        final int K = 40;
        for (int cycle = 0; cycle < K; cycle++) {
            // (1) Engine-step EACH section (upper then lower); write back to store + tracker.
            for (SubchunkKey key : new SubchunkKey[]{ upper, lower }) {
                SectionData data = store.get(key);
                char[]  matIx = speciesFromTracker(tracker.prior(DIM, key), lut);
                float[] mass  = new float[SEC_N];
                float[] temp  = new float[SEC_N];
                for (int i = 0; i < SEC_N; i++) { mass[i] = data.massAt(i); temp[i] = data.temperatureAt(i); }

                char[]  inMat  = matIx.clone();
                float[] before = mass.clone();

                StepTask task = new StepTask(key, matIx, mass, temp, voidHalo());
                List<StepResult> out = e.step(List.of(task), lut,
                        Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
                float[] outMass = out.get(0).mass();
                float[] outTemp = out.get(0).temperature();
                char[]  outMat  = out.get(0).material() != null ? out.get(0).material() : inMat;

                // Per-section engine conservation (the §9 gate, exactly as the Scheduler asserts it).
                assertTrue(StepValidator.massConservedPerSpecies(outMass, before, inMat, outMat, lut),
                        "§9 massConservedPerSpecies must accept the engine step for " + key
                                + " at cycle " + cycle);

                // Write engine output back into the store + the tracker (mimics writeBack + recordCellMaterials).
                for (int i = 0; i < SEC_N; i++) { data.setMass(i, outMass[i]); data.setTemperature(i, outTemp[i]); }
                store.put(key, data);
                tracker.record(DIM, key, idsFrom(outMat, lut));
            }

            // (2) Cross-section seam pass: upper is a donor over lower; lower donates to unloaded
            //     (0,-1,0) -> harmlessly skipped.
            List<ThermalWorld.BatchEntry> entries = List.of(
                    new ThermalWorld.BatchEntry(DIM, upper,
                            new StepTask(upper, new char[SEC_N], new float[SEC_N], new float[SEC_N], null)),
                    new ThermalWorld.BatchEntry(DIM, lower,
                            new StepTask(lower, new char[SEC_N], new float[SEC_N], new float[SEC_N], null)));
            CrossSectionSeamPass.run(store, tracker, entries, lut, waker);

            // (3) Conservation invariant EVERY cycle: total fluid+air mass across both sections held.
            double total =
                    fluidAirMass(store.get(upper), speciesFromTracker(tracker.prior(DIM, upper), lut), lut)
                  + fluidAirMass(store.get(lower), speciesFromTracker(tracker.prior(DIM, lower), lut), lut);
            assertEquals(initialTotal, total, convTol,
                    "total fluid+air mass must be conserved across the seam at cycle " + cycle
                            + " (initial=" + initialTotal + ", now=" + total + ")");
        }

        // --- End-state: the cascade happened and is clean. -----------------------------------------
        SectionData upAfter = store.get(upper);
        SectionData loAfter = store.get(lower);
        char[] upSpecies = speciesFromTracker(tracker.prior(DIM, upper), lut);
        char[] loSpecies = speciesFromTracker(tracker.prior(DIM, lower), lut);

        // (a) LOWER now holds substantial WATER it did not start with (water crossed the seam).
        double lowerWater = 0; int lowerWaterCells = 0;
        for (int i = 0; i < SEC_N; i++) {
            if (loSpecies[i] == WATER_IX) { lowerWater += loAfter.massAt(i); lowerWaterCells++; }
        }
        assertTrue(lowerWater > 1000.0,
                "lower section must hold substantial water that crossed the seam, was " + lowerWater
                        + " kg over " + lowerWaterCells + " cells");

        // (b) UPPER's seeded water FOOTPRINT has drained: the seed cells are no longer full water.
        double upperSeedWater = 0;
        for (int y = 0; y <= 3; y++)
            for (int x = 6; x <= 9; x++)
                for (int z = 6; z <= 9; z++) {
                    int i = sidx(x, y, z);
                    if (upSpecies[i] == WATER_IX) upperSeedWater += upAfter.massAt(i);
                }
        double seededWaterTotal = waterSeedCells * 1000.0;
        assertTrue(upperSeedWater < seededWaterTotal * 0.5,
                "upper seeded water footprint must have largely drained downward, "
                        + upperSeedWater + " of " + seededWaterTotal + " remains");

        // (c) NO residue: any upper cell the water fully left reads CLEAN — either AIR (~1.2 kg, swapped
        //     across the seam) OR a drained water-species cell at EXACTLY ~0 kg (the in-section fall
        //     drains the donor to empty while keeping its species). What is forbidden is a tiny
        //     FRACTIONAL water remnant (0 < mass < floor) left behind in a transited cell — the
        //     air-displacement swap + clean drain guarantee no such residue.
        for (int i = 0; i < SEC_N; i++) {
            if (upSpecies[i] == WATER_IX) {
                float m = upAfter.massAt(i);
                assertFalse(m > 1e-3f && m < water.maxMass() * 0.1f,
                        "no upper water cell may carry a tiny residue (0<mass<10% cap); cell " + i
                                + " held " + m + " kg");
            } else {
                // a non-water upper cell must read as clean air (the swap upgraded it), never void.
                assertEquals(AIR_IX, (int) upSpecies[i],
                        "a non-water upper cell must be AIR species (clean swap), cell " + i);
            }
        }

        // (d) The receiver/lower key was woken at least once.
        assertTrue(waker.waked.contains(lower), "lower (receiver) must have been woken by the seam pass");

        // --- Reseed-safety sub-assertion: a drained upper cell must NOT re-inflate to water. --------
        // Find a drained upper seed cell: still water SPECIES (in-section drain keeps species) but
        // emptied to ~0 kg. This is exactly the reseed-misfire candidate — a cell whose stored mass no
        // longer matches a "full" block — yet the tracker species equals the store species, so
        // MaterialChangeReseed.reseeds(...) is false and the cell is NOT re-inflated to 1000 kg.
        int drainedCell = -1;
        for (int y = 0; y <= 3 && drainedCell < 0; y++)
            for (int x = 6; x <= 9 && drainedCell < 0; x++)
                for (int z = 6; z <= 9; z++) {
                    int i = sidx(x, y, z);
                    if (upSpecies[i] == WATER_IX && upAfter.massAt(i) < 1f) { drainedCell = i; break; }
                }
        assertTrue(drainedCell >= 0, "expected at least one fully-drained (~0 kg) upper seed cell");
        // The reseed predicate must NOT fire for this drained cell (tracker species == store species).
        assertFalse(MaterialChangeReseed.reseeds(tracker.prior(DIM, upper)[drainedCell],
                        lut.get(upSpecies[drainedCell])),
                "reseeds(...) must be false for the drained cell (tracker matches store), so no re-inflation");

        // Run MaterialChangeReseed exactly as snapshot() would: prior = tracker signature, matIx =
        // the SAME tracker species (store and tracker agree post-cascade), so reseeds(...) is false.
        float[] upTempsNow = new float[SEC_N];
        float[] upMassNow  = new float[SEC_N];
        for (int i = 0; i < SEC_N; i++) { upTempsNow[i] = upAfter.temperatureAt(i); upMassNow[i] = upAfter.massAt(i); }
        float massBeforeReseed = upMassNow[drainedCell];
        MaterialChangeReseed.apply(tracker.prior(DIM, upper), upSpecies, lut, upTempsNow, upMassNow, 285f);
        assertEquals(massBeforeReseed, upMassNow[drainedCell], 1e-3f,
                "drained upper cell must NOT be re-inflated by the reseed (tracker matches store), was "
                        + upMassNow[drainedCell]);
        assertTrue(upMassNow[drainedCell] < 100f,
                "drained cell must stay near air mass, not jump to water's 1000 kg, was "
                        + upMassNow[drainedCell]);
    }
}
