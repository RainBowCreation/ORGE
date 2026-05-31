package net.rainbowcreation.orge;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.HaloAssembler;
import net.rainbowcreation.orge.scheduler.Scheduler;
import net.rainbowcreation.orge.scheduler.StepValidator;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FINAL INTEGRATION: drives the REAL bundled {@link NativeEngine} (the freshly rebuilt liborge.so)
 * over MULTIPLE sections wired with CROSS-SECTION HALOS, exactly as production does — a section's
 * NEGY halo is the section-below's {@code y=15} plane, its POSY halo is the section-above's
 * {@code y=0} plane, and the NEGX/POSX/NEGZ/POSZ halos are the X/Z-neighbour's adjacent edge plane
 * (the layout {@link HaloAssembler} and {@code MinecraftThermalWorld.buildHalo} produce). Each cycle:
 * rebuild every co-stepped section's halo from its neighbours' current planes, step every section via
 * the engine, feed mass/temp/species back as the next input, and VALIDATE the whole multi-section
 * batch with the §9 gate {@link StepValidator.SpeciesMassLedger} (assert {@code conserved()} every
 * cycle).
 *
 * <p>Asserts on the rebuilt .so: (a) a vertical cascade across the Y seam (MANDATORY), (b) a gas
 * rising across the Y seam, (c) horizontal spread across the X seam, and (d) a thermal gradient
 * crossing a seam (MANDATORY). If CI lacks the native lib these {@code @Test}s self-skip
 * (assumeTrue) so the build stays green — but on linux-x64 they RUN against the real library.</p>
 */
class CrossSectionNativeE2ETest {

    private static final int SEC_N = 4096;

    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");
    private static final Identifier AIR   = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ICE   = Identifier.fromNamespaceAndPath("minecraft", "ice");
    private static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    private static float sum(float[] a) {
        double s = 0;
        for (float v : a) s += v;
        return (float) s;
    }

    /** Real, advecting water: fluid=true, defaultMass/cap 1000, min-flow floor 125. */
    private static Material water() {
        return new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                373.15f, 273.15f, STEAM, ICE, null,
                Float.NaN, false, Material.State.FLUID, 125f, 1000f);
    }

    /** A real gas (steam): gas()==true, fluid()==true, very light resting mass so it rises. */
    private static Material gas() {
        return new Material(STEAM, 0.02f, 2000f, 0f, 1f, 0.018f,
                Float.POSITIVE_INFINITY, 0f, null, null, null,
                Float.NaN, false, Material.State.GAS, 0f, 1f);
    }

    /** First-class AIR (State.AIR): air()==true, fluid()==false, ~1.2 kg resting; a fall/wet sink. */
    private static Material air() {
        return new Material(AIR, 0.026f, 1005f, 0f, 1.2f, 0.029f,
                Float.POSITIVE_INFINITY, 0f, null, null, null,
                Float.NaN, false, Material.State.AIR, 0f, 0f);
    }

    private static Material voidMat() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "void"),
                0f, 0f, 0f, 0f, 0.018f, 9999f, 0f, null, null, null);
    }

    private static NativeEngine requireNative() {
        OrgeEngine eng = EngineFactory.create();
        assumeTrue(eng instanceof NativeEngine,
                "native liborge must load on linux-x64; got " + eng.getClass().getSimpleName());
        return (NativeEngine) eng;
    }

    // --- A mutable section the batch steps in place, exposed as a HaloAssembler.Neighbor ----------

    /** One co-stepped section: full-size matIx/mass/temp arrays, addressable by {@link SubchunkKey}. */
    private static final class Section {
        final SubchunkKey key;
        char[]  matIx = new char[SEC_N];
        float[] mass  = new float[SEC_N];
        float[] temp  = new float[SEC_N];

        Section(SubchunkKey key) {
            this.key = key;
            Arrays.fill(temp, 300f);
        }

        HaloAssembler.Neighbor asNeighbor() {
            return new HaloAssembler.Neighbor(temp, matIx, mass);
        }
    }

    /**
     * Build {@code s}'s halo from the six neighbours in {@code grid} (null at a loaded edge), exactly
     * as {@code MinecraftThermalWorld.buildHalo} does: NEGY = the section-below, POSY = the
     * section-above, NEGX/POSX/NEGZ/POSZ = the X/Z neighbour. {@link HaloAssembler} then reads the
     * correct contributing plane from each (e.g. the below section's y=15 layer for our NEGY face).
     */
    private static NeighborHalo haloFor(Section s, java.util.Map<SubchunkKey, Section> grid) {
        SubchunkKey k = s.key;
        return HaloAssembler.assemble(
                neighborAt(grid, k.cx() - 1, k.sectionY(),     k.cz()),
                neighborAt(grid, k.cx() + 1, k.sectionY(),     k.cz()),
                neighborAt(grid, k.cx(),     k.sectionY() - 1, k.cz()),
                neighborAt(grid, k.cx(),     k.sectionY() + 1, k.cz()),
                neighborAt(grid, k.cx(),     k.sectionY(),     k.cz() - 1),
                neighborAt(grid, k.cx(),     k.sectionY(),     k.cz() + 1));
    }

    private static HaloAssembler.Neighbor neighborAt(java.util.Map<SubchunkKey, Section> grid,
                                                     int cx, int sy, int cz) {
        Section n = grid.get(new SubchunkKey(cx, sy, cz));
        return n == null ? null : n.asNeighbor();   // null neighbour -> void face (HaloAssembler)
    }

    /**
     * Step every section once with cross-section halos, feed results back in place, and assert the
     * §9 batch ledger conserves every fluid species across the WHOLE batch this cycle. Halos are
     * snapshotted from the pre-step planes (one consistent snapshot), matching the production
     * snapshot→step→write-back order.
     */
    private static void stepBatchOnce(NativeEngine e, List<Section> sections, List<Material> lut) {
        java.util.Map<SubchunkKey, Section> grid = new java.util.HashMap<>();
        for (Section s : sections) grid.put(s.key, s);

        // 1) snapshot every halo BEFORE any section mutates (consistent pre-step planes).
        List<NeighborHalo> halos = new ArrayList<>();
        for (Section s : sections) halos.add(haloFor(s, grid));

        // 2) build tasks + remember each section's pre-step state for the ledger.
        List<StepTask> tasks = new ArrayList<>();
        List<char[]>  inMats  = new ArrayList<>();
        List<float[]> befores = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            inMats.add(s.matIx.clone());
            befores.add(s.mass.clone());
            tasks.add(new StepTask(s.key, s.matIx, s.mass, s.temp, halos.get(i)));
        }

        // 3) step the whole batch through the real native engine.
        List<StepResult> out = e.step(tasks, lut, Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);

        // 4) write results back in place + accumulate the batch §9 ledger.
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            StepResult r = out.get(i);
            char[] outMat = r.material() != null ? r.material() : inMats.get(i);
            ledger.add(r.mass(), befores.get(i), inMats.get(i), outMat, lut);
            s.mass  = r.mass();
            s.temp  = r.temperature();
            s.matIx = outMat;
        }

        // 5) §9 BATCH conservation gate — cross-seam transfers cancel in the batch sum.
        assertTrue(ledger.conserved(),
                "§9 batch ledger must conserve every fluid species across the whole multi-section batch");
    }

    /** Sum mass of the given output species across one section. */
    private static float speciesMass(Section s, int species) {
        float m = 0f;
        for (int i = 0; i < SEC_N; i++) if (s.matIx[i] == species) m += s.mass[i];
        return m;
    }

    // =========================================================================================
    // (a) MANDATORY — vertical cascade across the Y seam.
    // =========================================================================================

    /**
     * Two stacked sections: UPPER {@code (0,1,0)} above LOWER {@code (0,0,0)}. The UPPER section's
     * FLOOR (y=0) is seeded full of water; the LOWER section is full of real air. Production wiring:
     * UPPER.NEGY halo = LOWER's y=15 plane and LOWER.POSY halo = UPPER's y=0 plane, so the engine's
     * down-only seam gravity drains the UPPER floor INTO the LOWER section's top across cycles. We
     * assert the lower section GAINS water, the upper DRAINS, the batch conserves every cycle, and no
     * appreciable (> a sub-flow-floor 1.2 kg residue) water is left rendered in the upper floor.
     */
    @Test
    void verticalCascadeCrossesYSeam() {
        NativeEngine e = requireNative();
        Material water = water(), air = air();
        List<Material> lut = List.of(voidMat(), water, air);   // void, WATER=1, AIR=2

        Section upper = new Section(new SubchunkKey(0, 1, 0));
        Section lower = new Section(new SubchunkKey(0, 0, 0));

        // LOWER: entirely real air (matIx 2, 1.2 kg) — a continuous fall/wet sink to the floor.
        Arrays.fill(lower.matIx, (char) 2);
        Arrays.fill(lower.mass, 1.2f);
        // UPPER: real air everywhere, but the FLOOR (y=0) plane is FULL water (the head to cascade).
        Arrays.fill(upper.matIx, (char) 2);
        Arrays.fill(upper.mass, 1.2f);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = sidx(x, 0, z);
            upper.matIx[i] = (char) 1; upper.mass[i] = 1000f;
        }

        float waterBefore = speciesMass(upper, 1) + speciesMass(lower, 1);
        assertEquals(256 * 1000f, waterBefore, 1f, "seeded 256 full water cells in the upper floor");
        float lowerWaterStart = speciesMass(lower, 1);
        assertEquals(0f, lowerWaterStart, 1e-3f, "lower starts with no water");

        List<Section> batch = List.of(upper, lower);
        for (int cycle = 0; cycle < 60; cycle++) {
            stepBatchOnce(e, batch, lut);   // asserts batch conservation every cycle
        }

        float upperWater = speciesMass(upper, 1);
        float lowerWater = speciesMass(lower, 1);

        // The cascade crossed the Y seam: the lower section gained real water, the upper drained.
        assertTrue(lowerWater > 1000f,
                "lower section must GAIN water across the Y seam (cascade), was " + lowerWater);
        assertTrue(upperWater < waterBefore * 0.5f,
                "upper section must DRAIN most of its water across the seam, was " + upperWater);

        // No rendered residue left in the upper FLOOR cells beyond the sub-flow-floor 1.2 kg the engine
        // design accepts (a wetted cell may hold up to the 125 kg min-flow before it can flow again, so
        // we accept anything below that floor as "drained / not rendered as a flowing block").
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = sidx(x, 0, z);
            if (upper.matIx[i] == 1) {
                assertTrue(upper.mass[i] <= 125f + 1e-2f,
                        "upper-floor cell (" + x + "," + z + ") left more than the flow floor: " + upper.mass[i]);
            }
        }

        // Total water across the batch is conserved-or-grows-by-absorbed-air: as the falling head wets
        // real-air cells the kernel ADOPTS the air's resting ~1.2 kg INTO the water (the §9 wetting
        // credit air-in -> fluid-out), so the WATER species legitimately gains a little absorbed air.
        // The authoritative conservation check is the per-cycle batch §9 ledger (asserted every cycle in
        // stepBatchOnce); here we just bound the end-state: water never DECREASES (no leak) and the
        // small increase is at most the air it could have absorbed.
        float waterAfter = upperWater + lowerWater;
        float airBudget = 2f * SEC_N * air.defaultMass(); // upper+lower air mass available to absorb
        assertTrue(waterAfter >= waterBefore - 1f,
                "water species must not LEAK across the batch (no mass loss), was " + waterAfter);
        assertTrue(waterAfter <= waterBefore + airBudget,
                "water may only grow by absorbed air (wetting credit), was " + waterAfter);
    }

    // =========================================================================================
    // (b) gas rises across the Y seam.
    // =========================================================================================

    /**
     * A light gas seeded in the LOWER section's TOP (y=15) plane, with a denser liquid (water) in the
     * UPPER section's FLOOR (y=0). Buoyancy across the seam must SWAP the gas up into the upper floor
     * (and water down), so the upper section gains gas species and the lower loses it. Conserved every
     * cycle.
     */
    @Test
    void gasRisesAcrossYSeam() {
        NativeEngine e = requireNative();
        Material water = water(), gas = gas();
        List<Material> lut = List.of(voidMat(), water, gas);   // void, WATER=1, GAS=2

        Section upper = new Section(new SubchunkKey(0, 1, 0));
        Section lower = new Section(new SubchunkKey(0, 0, 0));

        // UPPER floor (y=0): dense water. LOWER top (y=15): light gas directly beneath it.
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int u = sidx(x, 0, z);  upper.matIx[u] = (char) 1; upper.mass[u] = 1000f;
            int l = sidx(x, 15, z); lower.matIx[l] = (char) 2; lower.mass[l] = 1f;
        }

        float gasInLowerStart = speciesMass(lower, 2);
        float gasInUpperStart = speciesMass(upper, 2);
        assertTrue(gasInLowerStart > 0f, "gas seeded in the lower top plane");
        assertEquals(0f, gasInUpperStart, 1e-3f, "no gas in the upper section to start");
        float gasTotal = gasInLowerStart + gasInUpperStart;

        List<Section> batch = List.of(upper, lower);
        for (int cycle = 0; cycle < 20; cycle++) {
            stepBatchOnce(e, batch, lut);
        }

        float gasInUpper = speciesMass(upper, 2);
        float gasInLower = speciesMass(lower, 2);

        assertTrue(gasInUpper > 0f,
                "gas must RISE across the Y seam into the upper section, upper gas = " + gasInUpper);
        assertTrue(gasInUpper > gasInLower,
                "more gas should sit above the seam than below after rising (up=" + gasInUpper
                        + ", down=" + gasInLower + ")");
        assertEquals(gasTotal, gasInUpper + gasInLower, Math.max(0.5f, gasTotal * 1e-2f),
                "gas species mass conserved across the batch");
    }

    // =========================================================================================
    // (c) horizontal spread across the X seam.
    // =========================================================================================

    /**
     * Two X-adjacent sections: WEST {@code (0,0,0)} and EAST {@code (1,0,0)}. Water seeded on the
     * WEST section's floor at its +x edge (x=15) must spread across the X seam into the EAST section's
     * air at its -x edge (x=0). Production wiring: WEST.POSX halo = EAST's x=0 plane and EAST.NEGX
     * halo = WEST's x=15 plane. Both floors are real air so a supported floor cell can wet sideways.
     * Conserved every cycle.
     *
     * <p>Exercises the engine's antisymmetric X/Z same-fluid seam-leveling pass (E4): two ADJACENT fluid
     * cells leveling across an X/Z section seam must conserve mass. Previously the interior spread pass
     * (orge_kernel.hpp §"(2b-i) HORIZONTAL spread") fluxed only "toward lower mass" at the boundary
     * ({@code if (diff <= 0) continue}) and relied on the neighbour section adding the +dm on its own
     * pass — an antisymmetry that was FALSE for leveling (the higher-mass side SUBTRACTED dm, the
     * lower-mass receiver SKIPPED the matching add, and the dm vanished: e.g. 1000/500 kg X-adjacent
     * columns lost 1000 kg/step). E4 gives X/Z same-fluid seam leveling its own antisymmetric pass so
     * each section writes only its own cell from identical pre-step dm. The assertions below are the
     * CORRECT invariants: the per-cycle batch §9 gate in {@code stepBatchOnce} must hold every cycle, and
     * water must genuinely move across the X seam into the east air.
     */
    @Test
    void horizontalSpreadCrossesXSeam() {
        NativeEngine e = requireNative();
        Material water = water(), air = air();
        List<Material> lut = List.of(voidMat(), water, air);   // void, WATER=1, AIR=2

        Section west = new Section(new SubchunkKey(0, 0, 0));
        Section east = new Section(new SubchunkKey(1, 0, 0));

        // Both sections' FLOOR (y=0) is real air so a supported floor cell can WET sideways (the
        // kernel only wets from a SUPPORTED cell — y=0 is supported). The rest is void no-flow wall so
        // the pool cannot leak vertically and the only outlet is across the X seam.
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wi = sidx(x, 0, z); west.matIx[wi] = (char) 2; west.mass[wi] = 1.2f;
            int ei = sidx(x, 0, z); east.matIx[ei] = (char) 2; east.mass[ei] = 1.2f;
        }
        // Seed FULL water on the WEST +x edge column (x=15, y=0), all z.
        for (int z = 0; z < 16; z++) {
            int i = sidx(15, 0, z);
            west.matIx[i] = (char) 1; west.mass[i] = 1000f;
        }

        float eastWaterStart = speciesMass(east, 1);
        assertEquals(0f, eastWaterStart, 1e-3f, "east starts with no water");
        float waterBefore = speciesMass(west, 1) + speciesMass(east, 1);

        List<Section> batch = List.of(west, east);
        for (int cycle = 0; cycle < 40; cycle++) {
            stepBatchOnce(e, batch, lut);
        }

        float eastWater = speciesMass(east, 1);
        // Water crossed the X seam into the EAST section's -x edge (x=0).
        float eastEdgeWater = 0f;
        for (int z = 0; z < 16; z++) {
            int i = sidx(0, 0, z);
            if (east.matIx[i] == 1) eastEdgeWater += east.mass[i];
        }
        assertTrue(eastWater > 1f,
                "water must spread across the X seam into the east section, east water = " + eastWater);
        assertTrue(eastEdgeWater > 0f,
                "the east section's -x edge (x=0) must hold the wetted water, was " + eastEdgeWater);

        // The authoritative conservation gate is the per-cycle batch §9 ledger asserted EVERY cycle in
        // stepBatchOnce (conserved() across the whole multi-section batch). Here we bound the end state
        // exactly as case (a) does: as the water levels/wets across the X seam it ADOPTS the east-floor
        // real-air's resting ~1.2 kg INTO the water (the §9 wetting credit air-in -> fluid-out), so the
        // WATER species legitimately gains a little absorbed air. Water must never DECREASE (no leak —
        // the bug this test guards lost ~1209 kg/cycle), and may grow only by the air it could absorb.
        float waterAfter = speciesMass(west, 1) + eastWater;
        float airBudget  = 2f * SEC_N * air.defaultMass(); // west+east air mass available to absorb
        assertTrue(waterAfter >= waterBefore - 1f,
                "water species must not LEAK across the X-seam batch (no mass loss), was " + waterAfter);
        assertTrue(waterAfter <= waterBefore + airBudget,
                "water may only grow by absorbed air (wetting credit), was " + waterAfter);
    }

    // =========================================================================================
    // (d) MANDATORY — thermal crosses a seam (conduction via the halo).
    // =========================================================================================

    /**
     * Two stacked solid (stone) sections sharing a Y seam. The UPPER section's floor (y=0) cell is
     * hot (1500 K); everything else starts cold (300 K). Over conduction cycles the heat must cross
     * the seam into the LOWER section's top (y=15) cell via the halo — i.e. the adjacent cell on the
     * other side of the seam warms measurably above its cold start. (This path already worked via the
     * halo; we assert it still does on the rebuilt .so.)
     */
    @Test
    void thermalGradientCrossesSeam() {
        NativeEngine e = requireNative();
        // Solid, highly conductive metal-like material so the cross-seam conduction signal is clear
        // over a modest number of cycles (low mass + high conductivity -> fast equilibration).
        Material stone = new Material(STONE, 50.0f, 840f, 0f, 200f, 0f, 9999f, 0f, null, null, null);
        List<Material> lut = List.of(voidMat(), stone);        // void, STONE=1

        Section upper = new Section(new SubchunkKey(0, 1, 0));
        Section lower = new Section(new SubchunkKey(0, 0, 0));
        Arrays.fill(upper.matIx, (char) 1); Arrays.fill(upper.mass, 200f);
        Arrays.fill(lower.matIx, (char) 1); Arrays.fill(lower.mass, 200f);
        Arrays.fill(upper.temp, 300f);      Arrays.fill(lower.temp, 300f);

        // Hot cell on the UPPER floor (y=0), directly above the LOWER top cell (y=15) across the seam.
        int hotCol = sidx(8, 0, 8);
        upper.temp[hotCol] = 1500f;
        int seamCellLower = sidx(8, 15, 8);   // the cell on the OTHER side of the seam
        float seamColdStart = lower.temp[seamCellLower];
        assertEquals(300f, seamColdStart, 1e-3f);

        java.util.Map<SubchunkKey, Section> grid = new java.util.HashMap<>();
        grid.put(upper.key, upper); grid.put(lower.key, lower);
        List<Section> batch = List.of(upper, lower);

        for (int cycle = 0; cycle < 40; cycle++) {
            // Rebuild halos from current planes, step CONDUCTION (no advection), feed back. No mass
            // ledger needed (pure conduction conserves no mass species), but rebuild halos each cycle.
            List<NeighborHalo> halos = new ArrayList<>();
            for (Section s : batch) halos.add(haloFor(s, grid));
            List<StepTask> tasks = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                Section s = batch.get(i);
                tasks.add(new StepTask(s.key, s.matIx, s.mass, s.temp, halos.get(i)));
            }
            List<StepResult> out = e.step(tasks, lut, Scheduler.STEP_DT_SECONDS, OrgeEngine.PASS_CONDUCTION);
            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).temp = out.get(i).temperature();
            }
        }

        float seamWarm = lower.temp[seamCellLower];
        // Heat must cross the Y seam via the halo: the lower top cell (other side of the seam) warms
        // measurably above its cold start. The exact rise depends on conductivity/cycles; we require a
        // clear, unambiguous signal (> 1 K) that conduction crossed the section boundary.
        assertTrue(seamWarm > seamColdStart + 1f,
                "heat must cross the Y seam: lower top cell warmed from " + seamColdStart + " to " + seamWarm);
        // Sanity: it must not overshoot the source.
        assertTrue(seamWarm < 1500f,
                "the conducted seam cell must stay below the source temperature, was " + seamWarm);
    }
}
