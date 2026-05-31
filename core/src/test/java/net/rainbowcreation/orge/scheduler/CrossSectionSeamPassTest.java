package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.RegionStore;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless TDD tests for {@link CrossSectionSeamPass#run} — the section-store + tracker
 * orchestration that drives {@link CrossSectionFluidLogic} across a stacked Y seam so an
 * UPPER section's floor (y=0) falls into the LOWER section's top (y=15).
 *
 * <p>Tests target the pure helper directly (no Minecraft server) using a real
 * {@link SectionStore} + {@link CellMaterialTracker} and a capturing {@link CrossSectionSeamPass.FlowWaker}.
 */
class CrossSectionSeamPassTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");

    private static final char WATER_IX = 1;
    private static final char AIR_IX = 3;

    /* ------------------------------------------------------------------ */
    /*  Fixtures: LUT, store, tracker, waker                               */
    /* ------------------------------------------------------------------ */

    private static Material mat(String name, Material.State state, float defaultMass, float maxMass) {
        return new Material(
                Identifier.fromNamespaceAndPath("orge", name),
                0.6f, 4186f, 0.001f, defaultMass, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null,
                Float.NaN, false, state, 0f, maxMass);
    }

    /** LUT: 0=VOID, 1=WATER, 2=LAVA, 3=AIR. */
    private static List<Material> lut() {
        List<Material> lut = new ArrayList<>();
        lut.add(MaterialLut.VOID);                                       // 0
        lut.add(mat("water", Material.State.FLUID, 1000f, 1000f));       // 1
        lut.add(mat("lava", Material.State.FLUID, 3100f, 3100f));        // 2
        lut.add(mat("air", Material.State.AIR, 1.2f, 1.2f));             // 3
        return lut;
    }

    private static final AmbientProvider AMB = new AmbientProvider() {
        @Override public float ambientTemperatureK(SubchunkKey k) { return 285f; }
        @Override public float ambientMassKg(SubchunkKey k) { return 1.2f; }
    };

    private SectionStore newStore(Path world) {
        return new SectionStore(new RegionStore(world), AMB);
    }

    /** Capturing waker collecting (dim,key) pairs. */
    private static final class CapturingWaker implements CrossSectionSeamPass.FlowWaker {
        final List<SubchunkKey> waked = new ArrayList<>();
        @Override public void wake(Identifier dim, SubchunkKey key) { waked.add(key); }
    }

    /** Full cell index for plane column c (x-fastest) at a given y. */
    private static int idx(int c, int y) {
        int x = c & 15;
        int z = (c >> 4) & 15;
        return x + 16 * y + 256 * z;
    }

    /** A FULL section with every cell at (temp, mass). */
    private static SectionData fullSection(float temp, float mass) {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, temp);
        java.util.Arrays.fill(m, mass);
        return SectionData.full(t, m);
    }

    /** Uniform prior signature (one id everywhere). */
    private static Identifier[] uniformPrior(Identifier id) {
        Identifier[] p = new Identifier[SectionData.CELLS];
        java.util.Arrays.fill(p, id);
        return p;
    }

    /** One batch entry for the UPPER (donor) section. StepTask geometry is irrelevant to the pass. */
    private static ThermalWorld.BatchEntry entry(SubchunkKey key) {
        return new ThermalWorld.BatchEntry(DIM, key,
                new StepTask(key, new char[SectionData.CELLS], new float[SectionData.CELLS],
                        new float[SectionData.CELLS], null));
    }

    /* ------------------------------------------------------------------ */
    /*  Tests                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    void fallIntoAirAcrossSeam(@TempDir Path world) {
        SectionStore store = newStore(world);
        CellMaterialTracker tracker = new CellMaterialTracker();
        CapturingWaker waker = new CapturingWaker();

        SubchunkKey upper = new SubchunkKey(0, 1, 0);
        SubchunkKey lower = new SubchunkKey(0, 0, 0);

        // Upper floor (y=0) = water 1000 @ 300K; everything else in the section can be anything
        // (only the y=0 plane matters). Use FULL sections so individual cells survive.
        SectionData upperData = fullSection(300f, 1000f);
        SectionData lowerData = fullSection(285f, 1.2f);  // top = air
        store.put(upper, upperData);
        store.put(lower, lowerData);

        tracker.record(DIM, upper, uniformPrior(WATER));
        tracker.record(DIM, lower, uniformPrior(AIR));

        List<ThermalWorld.TouchedSection> touched =
                CrossSectionSeamPass.run(store, tracker, List.of(entry(upper)), lut(), waker);

        // Both sections touched and returned.
        assertEquals(2, touched.size(), "both donor and receiver returned");

        int sampleC = 5 + 16 * 7; // arbitrary column
        // Receiver y=15 now holds the water.
        SectionData lowerAfter = store.get(lower);
        assertEquals(1000f, lowerAfter.massAt(idx(sampleC, 15)), 1e-3f, "receiver filled with water mass");
        assertEquals(300f, lowerAfter.temperatureAt(idx(sampleC, 15)), 1e-3f, "receiver got donor temp");

        // Donor y=0 now holds the air residue (swap).
        SectionData upperAfter = store.get(upper);
        assertEquals(1.2f, upperAfter.massAt(idx(sampleC, 0)), 1e-3f, "donor drained to air mass");

        // Tracker updated to the new species at the seam plane.
        Identifier[] priorLowerAfter = tracker.prior(DIM, lower);
        Identifier[] priorUpperAfter = tracker.prior(DIM, upper);
        assertEquals(WATER, priorLowerAfter[idx(sampleC, 15)], "receiver tracker records water");
        assertEquals(AIR, priorUpperAfter[idx(sampleC, 0)], "donor tracker records air");

        // Both donor and receiver waked.
        assertTrue(waker.waked.contains(lower), "receiver waked");
        assertTrue(waker.waked.contains(upper), "donor waked");
    }

    @Test
    void depositIntoSameFluidConserves(@TempDir Path world) {
        SectionStore store = newStore(world);
        CellMaterialTracker tracker = new CellMaterialTracker();
        CapturingWaker waker = new CapturingWaker();

        SubchunkKey upper = new SubchunkKey(0, 1, 0);
        SubchunkKey lower = new SubchunkKey(0, 0, 0);

        SectionData upperData = fullSection(300f, 1000f);   // water 1000
        SectionData lowerData = fullSection(280f, 300f);    // water 300 (cap 1000)
        store.put(upper, upperData);
        store.put(lower, lowerData);

        tracker.record(DIM, upper, uniformPrior(WATER));
        tracker.record(DIM, lower, uniformPrior(WATER));

        List<ThermalWorld.TouchedSection> touched =
                CrossSectionSeamPass.run(store, tracker, List.of(entry(upper)), lut(), waker);

        assertEquals(2, touched.size());

        int c = 0;
        SectionData lowerAfter = store.get(lower);
        SectionData upperAfter = store.get(upper);
        float lower15 = lowerAfter.massAt(idx(c, 15));
        float upper0 = upperAfter.massAt(idx(c, 0));
        assertEquals(1000f, lower15, 1e-3f, "receiver filled to cap");
        assertEquals(300f, upper0, 1e-3f, "donor drained by the 700 moved");
        // Conservation: 1000+300 before == lower15 + upper0 after.
        assertEquals(1300f, lower15 + upper0, 1e-3f, "mass conserved across the column");
    }

    @Test
    void unloadedReceiverIsNoOp(@TempDir Path world) {
        SectionStore store = newStore(world);
        CellMaterialTracker tracker = new CellMaterialTracker();
        CapturingWaker waker = new CapturingWaker();

        SubchunkKey upper = new SubchunkKey(0, 1, 0);
        SubchunkKey lower = new SubchunkKey(0, 0, 0);

        store.put(upper, fullSection(300f, 1000f));
        tracker.record(DIM, upper, uniformPrior(WATER));
        // lower NOT put -> not loaded.

        List<ThermalWorld.TouchedSection> touched =
                CrossSectionSeamPass.run(store, tracker, List.of(entry(upper)), lut(), waker);

        assertTrue(touched.isEmpty(), "unloaded receiver -> empty result");
        assertTrue(waker.waked.isEmpty(), "unloaded receiver -> no wake");
        assertFalse(store.hasSection(lower), "receiver still unloaded");
    }

    @Test
    void untrackedReceiverWakesButDoesNotTransfer(@TempDir Path world) {
        SectionStore store = newStore(world);
        CellMaterialTracker tracker = new CellMaterialTracker();
        CapturingWaker waker = new CapturingWaker();

        SubchunkKey upper = new SubchunkKey(0, 1, 0);
        SubchunkKey lower = new SubchunkKey(0, 0, 0);

        SectionData upperData = fullSection(300f, 1000f);
        store.put(upper, upperData);
        store.put(lower, fullSection(285f, 1.2f));
        tracker.record(DIM, upper, uniformPrior(WATER));
        // lower has a section but NO tracker signature.

        List<ThermalWorld.TouchedSection> touched =
                CrossSectionSeamPass.run(store, tracker, List.of(entry(upper)), lut(), waker);

        assertTrue(touched.isEmpty(), "untracked receiver -> empty result this cycle");
        assertTrue(waker.waked.contains(lower), "untracked receiver IS waked (becomes tracked next cycle)");
        // Donor unchanged.
        assertEquals(1000f, store.get(upper).massAt(idx(0, 0)), 0f, "donor unchanged");
    }

    @Test
    void noFluidDonorIsNoOp(@TempDir Path world) {
        SectionStore store = newStore(world);
        CellMaterialTracker tracker = new CellMaterialTracker();
        CapturingWaker waker = new CapturingWaker();

        SubchunkKey upper = new SubchunkKey(0, 1, 0);
        SubchunkKey lower = new SubchunkKey(0, 0, 0);

        // Donor floor is air -> no fluid to move.
        store.put(upper, fullSection(285f, 1.2f));
        store.put(lower, fullSection(285f, 1.2f));
        tracker.record(DIM, upper, uniformPrior(AIR));
        tracker.record(DIM, lower, uniformPrior(AIR));

        List<ThermalWorld.TouchedSection> touched =
                CrossSectionSeamPass.run(store, tracker, List.of(entry(upper)), lut(), waker);

        assertTrue(touched.isEmpty(), "air donor -> nothing changes");
        assertTrue(waker.waked.isEmpty(), "air donor -> no wake");
    }

    @Test
    void reseedSafetyTrackerMatchesDrainedStore(@TempDir Path world) {
        // After a successful fall, the tracker at the drained donor column must record AIR (the new
        // store state), so the next snapshot's reseed will NOT mistake it for a player edit and
        // re-inflate the mass ("mass from nothing").
        SectionStore store = newStore(world);
        CellMaterialTracker tracker = new CellMaterialTracker();
        CapturingWaker waker = new CapturingWaker();

        SubchunkKey upper = new SubchunkKey(0, 1, 0);
        SubchunkKey lower = new SubchunkKey(0, 0, 0);

        store.put(upper, fullSection(300f, 1000f));
        store.put(lower, fullSection(285f, 1.2f));
        tracker.record(DIM, upper, uniformPrior(WATER));
        tracker.record(DIM, lower, uniformPrior(AIR));

        CrossSectionSeamPass.run(store, tracker, List.of(entry(upper)), lut(), waker);

        int c = 9 + 16 * 3;
        Identifier[] priorUpper = tracker.prior(DIM, upper);
        // The tracker says AIR at the drained cell; the store says ~1.2 (air) mass. They agree, so a
        // reseed driven by this signature would see "air cell still air" and not re-inflate to water.
        assertEquals(AIR, priorUpper[idx(c, 0)], "drained donor tracked as AIR, not full-mass water");
        assertEquals(1.2f, store.get(upper).massAt(idx(c, 0)), 1e-3f, "store mass matches the air species");
    }

    /**
     * Three stacked sections: sectionY 2 (top) over 1 (middle) over 0 (bottom).
     * All loaded and tracked. Water sits on the floor of sections 2 and 1; air fills the top of
     * sections 1 and 0. Section 1 is BOTH a receiver (from section 2's seam) AND a donor (over
     * section 0) within the SAME run.
     *
     * <p>Assertions:
     * <ul>
     *   <li>Seam 2→1: section 1's y=15 receives water from section 2's y=0.</li>
     *   <li>Seam 1→0: section 0's y=15 receives water from section 1's y=0.</li>
     *   <li>Mass is globally conserved across all three sections (sum of seam-plane cells).</li>
     *   <li>Section 1 appears EXACTLY ONCE in the returned TouchedSection list (dedup fix).</li>
     *   <li>Section 1 is waked (dedup: at most once).</li>
     * </ul>
     */
    @Test
    void multiPairCascadeThreeSections(@TempDir Path world) {
        SectionStore store = newStore(world);
        CellMaterialTracker tracker = new CellMaterialTracker();
        CapturingWaker waker = new CapturingWaker();

        SubchunkKey sec2 = new SubchunkKey(0, 2, 0);  // top donor
        SubchunkKey sec1 = new SubchunkKey(0, 1, 0);  // middle: receiver from 2, donor to 0
        SubchunkKey sec0 = new SubchunkKey(0, 0, 0);  // bottom receiver

        // Section 2: floor (y=0) = water 1000 kg @ 300 K, rest doesn't matter for seam logic.
        // Section 1: top (y=15) = air 1.2 kg @ 285 K (receives from 2);
        //            floor (y=0) = water 800 kg @ 295 K (donates to 0).
        // Section 0: top (y=15) = air 1.2 kg @ 285 K (receives from 1).
        //
        // Build full sections so individual cells survive SectionData promotion:
        SectionData data2 = fullSection(300f, 1000f);   // all water 1000
        SectionData data1 = fullSection(285f, 1.2f);    // all air 1.2 (top=air, floor will be overridden)
        SectionData data0 = fullSection(285f, 1.2f);    // all air 1.2

        // Override section 1's floor (y=0) to water 800 @ 295 K.
        for (int c = 0; c < NeighborHalo.FACE_CELLS; c++) {
            int ia = idx(c, 0);
            data1.setMass(ia, 800f);
            data1.setTemperature(ia, 295f);
        }

        store.put(sec2, data2);
        store.put(sec1, data1);
        store.put(sec0, data0);

        // Tracker: sec2 = all water, sec1 = water at y=0 / air elsewhere, sec0 = all air.
        // Use uniformPrior for sec2 and sec0; for sec1 build a mixed prior.
        Identifier[] prior1 = uniformPrior(AIR);
        for (int c = 0; c < NeighborHalo.FACE_CELLS; c++) {
            prior1[idx(c, 0)] = WATER;
        }
        tracker.record(DIM, sec2, uniformPrior(WATER));
        tracker.record(DIM, sec1, prior1);
        tracker.record(DIM, sec0, uniformPrior(AIR));

        // entries: sec2 is donor for the 2→1 seam; sec1 is donor for the 1→0 seam.
        List<ThermalWorld.BatchEntry> entries = List.of(entry(sec2), entry(sec1));

        // Capture mass before across ALL seam planes (y=0 of donors + y=15 of receivers).
        int sampleC = 3 + 16 * 5;
        float mass2_y0_before = store.get(sec2).massAt(idx(sampleC, 0));  // 1000
        float mass1_y15_before = store.get(sec1).massAt(idx(sampleC, 15)); // 1.2 (air)
        float mass1_y0_before = store.get(sec1).massAt(idx(sampleC, 0));   // 800
        float mass0_y15_before = store.get(sec0).massAt(idx(sampleC, 15)); // 1.2 (air)
        double globalBefore = mass2_y0_before + mass1_y15_before + mass1_y0_before + mass0_y15_before;

        List<ThermalWorld.TouchedSection> touched =
                CrossSectionSeamPass.run(store, tracker, entries, lut(), waker);

        // --- Seam 2→1 transferred: section 1's y=15 should now hold water from section 2's y=0. ---
        SectionData after1 = store.get(sec1);
        float mass1_y15_after = after1.massAt(idx(sampleC, 15));
        float mass2_y0_after = store.get(sec2).massAt(idx(sampleC, 0));
        assertTrue(mass1_y15_after > mass1_y15_before,
                "section 1 y=15 received fluid from section 2 (was " + mass1_y15_before + ", now " + mass1_y15_after + ")");
        assertTrue(mass2_y0_after < mass2_y0_before,
                "section 2 y=0 drained after donating to section 1");

        // --- Seam 1→0 transferred: section 0's y=15 should now hold water from section 1's y=0. ---
        SectionData after0 = store.get(sec0);
        float mass0_y15_after = after0.massAt(idx(sampleC, 15));
        float mass1_y0_after = after1.massAt(idx(sampleC, 0));
        assertTrue(mass0_y15_after > mass0_y15_before,
                "section 0 y=15 received fluid from section 1 (was " + mass0_y15_before + ", now " + mass0_y15_after + ")");
        assertTrue(mass1_y0_after < mass1_y0_before,
                "section 1 y=0 drained after donating to section 0");

        // --- Global mass conservation across all four seam planes. ---
        double globalAfter = mass2_y0_after + mass1_y15_after + mass1_y0_after + mass0_y15_after;
        assertEquals(globalBefore, globalAfter, 1e-2,
                "global mass conserved across all three sections (before=" + globalBefore + ", after=" + globalAfter + ")");

        // --- Dedup: section 1 appears EXACTLY ONCE in the returned list (it is both receiver and donor). ---
        long sec1Count = touched.stream()
                .filter(ts -> ts.key().equals(sec1))
                .count();
        assertEquals(1, sec1Count,
                "section 1 must appear exactly once in TouchedSection list (dedup fix); found " + sec1Count);

        // All three sections should appear in the result.
        long sec2Count = touched.stream().filter(ts -> ts.key().equals(sec2)).count();
        long sec0Count = touched.stream().filter(ts -> ts.key().equals(sec0)).count();
        assertEquals(1, sec2Count, "section 2 appears exactly once");
        assertEquals(1, sec0Count, "section 0 appears exactly once");
        assertEquals(3, touched.size(), "exactly 3 distinct sections touched");

        // --- Section 1 is waked (at most once due to dedup). ---
        long sec1WakeCount = waker.waked.stream().filter(k -> k.equals(sec1)).count();
        assertTrue(sec1WakeCount >= 1, "section 1 was waked");
        assertEquals(1, sec1WakeCount, "section 1 waked exactly once (wake dedup)");
    }
}
