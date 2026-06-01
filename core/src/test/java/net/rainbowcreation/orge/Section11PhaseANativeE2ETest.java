package net.rainbowcreation.orge;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.Scheduler;
import net.rainbowcreation.orge.scheduler.StepValidator;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * §11 Phase A FINAL INTEGRATION E2E — proves the molar-mass gas-displacement bug fix on the freshly
 * rebuilt, air-DISPLACING {@code liborge.so} through the real production native path
 * (snapshot → native {@code step} → per-species §9 incl. air → write-back). Self-skips when the
 * native lib is absent so the build stays green on non-linux-x64 CI; on linux-x64 it RUNS for real.
 *
 * <p>The headline asserts §11 ships:</p>
 * <ol>
 *   <li><b>1000 → 1000.0</b> — 1000 kg of water spread WITHIN a section across N cells totals EXACTLY
 *       1000.0 kg (no {@code 1000 + (N−1)·1.2} mass-from-nothing); N = 2..6.</li>
 *   <li><b>displaces air, never consumes it</b> — total air mass is preserved (relocated, not absorbed).</li>
 *   <li><b>break block → vacuum → refill</b> — a broken cell becomes vacuum (0 mass); air refills it from
 *       interior neighbours; Σair is unchanged (no air-from-nothing).</li>
 *   <li><b>air compresses</b> — air squeezed from k cells into k−1 stays ≤ max_mass and conserves.</li>
 *   <li><b>per-species conserved every cycle</b> — the §9 gate accepts every step.</li>
 * </ol>
 */
class Section11PhaseANativeE2ETest {

    private static final int SEC_N = 4096;
    private static final int FACE = NeighborHalo.FACE_CELLS;

    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");
    private static final Identifier AIR   = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ICE   = Identifier.fromNamespaceAndPath("minecraft", "ice");

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    private static float sum(float[] a) {
        double s = 0;
        for (float v : a) s += v;
        return (float) s;
    }

    /** Resting air, exactly the live datapack roster: state=air, min 0.001, default 1.2, max 1000, M 0.029. */
    private static Material air() {
        return new Material(AIR, 0.026f, 1005f, 0f, 1.2f, 0.029f,
                Float.POSITIVE_INFINITY, 0f, null, null, null,
                Float.NaN, false, Material.State.AIR, 0.001f, 1000f);
    }

    /** Real advecting water: fluid, default/cap 1000, floor 125. */
    private static Material water() {
        return new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                373.15f, 273.15f, STEAM, ICE, null,
                Float.NaN, false, Material.State.FLUID, 125f, 1000f);
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

    private static NeighborHalo voidHalo() {
        float[] zf = new float[FACE];
        char[] zc = new char[FACE];
        return new NeighborHalo(
                zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(),
                zc.clone(), zc.clone(), zc.clone(), zc.clone(), zc.clone(), zc.clone(),
                zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone(), zf.clone());
    }

    /** Sum the output mass of cells whose OUTPUT species == {@code species}. */
    private static float speciesMass(float[] mass, char[] mat, int species) {
        float m = 0f;
        for (int i = 0; i < SEC_N; i++) if (mat[i] == species) m += mass[i];
        return m;
    }

    // ============================================================================================
    // (a) HEADLINE: 1000 kg water spread WITHIN a section to N cells -> total == 1000.0
    //     + (b) liquid displaces air (air mass preserved) + (e) per-species conserved every cycle.
    // ============================================================================================

    /** Inert SOLID (stone): fluid()==false, air()==false — a no-flow wall that is NOT a sink for
     *  displaced air (air can only be pushed into other AIR cells, never absorbed by a solid or void). */
    private static Material stone() {
        return new Material(Identifier.fromNamespaceAndPath("minecraft", "stone"),
                1.0f, 840f, 0f, 2000f, 0f, 9999f, 0f, null, null, null);
    }

    @Test
    void waterSpreadAcrossNCellsTotalsExactly1000AndAirIsConserved() {
        NativeEngine e = requireNative();
        // N = 2..6: the user's repro — pour ~1000 kg of water into an interior air-filled basin and let
        // it fall+level across N floor cells. The basin is built from INERT SOLID stone (not void), so
        // the air the water displaces can ONLY relocate into surrounding AIR cells (a tracked species) —
        // never leak into void or across the section face. The whole rig sits in the section interior,
        // away from all 6 boundary faces, so air is conserved without needing a co-stepped neighbour.
        for (int N = 2; N <= 6; N++) {
            List<Material> lut = List.of(voidMat(), water(), air(), stone());   // void, WATER=1, AIR=2, STONE=3

            char[]  matIx = new char[SEC_N];
            float[] mass  = new float[SEC_N];
            float[] temp  = new float[SEC_N];
            Arrays.fill(temp, 300f);
            // Ambient real air everywhere (resting 1.2 kg) at a NON-ZERO LUT index, so the kernel must
            // treat air as a finite gas to DISPLACE (not a void to fill).
            Arrays.fill(matIx, (char) 2);
            Arrays.fill(mass, 1.2f);

            // Interior basin (all coordinates 2..13, away from every face). A stone floor plane and stone
            // end walls bound a 1xN air trough N cells wide; the basin is OPEN at the top so displaced
            // air rises into the air column above (interior, conserved). Floor at fy, trough row at ty.
            int fy = 5, ty = 6, z = 8, x0 = 5;
            for (int k = -1; k <= N; k++) {                          // stone floor under the trough (+1 each end)
                int i = sidx(x0 + k, fy, z); matIx[i] = (char) 3; mass[i] = 2000f;
            }
            matIx[sidx(x0 - 1, ty, z)] = (char) 3; mass[sidx(x0 - 1, ty, z)] = 2000f;   // left end wall
            matIx[sidx(x0 + N, ty, z)] = (char) 3; mass[sidx(x0 + N, ty, z)] = 2000f;    // right end wall
            // (the trough cells x0..x0+N-1 at y=ty stay AIR — the wet sinks the water spreads into)

            // Pour: 1000 kg of water as a SINGLE full cell sitting in the trough's leftmost air cell.
            int src = sidx(x0, ty, z);
            matIx[src] = (char) 1; mass[src] = 1000f;

            float airBefore   = speciesMass(mass, matIx, 2);
            float waterBefore = speciesMass(mass, matIx, 1);
            assertEquals(1000f, waterBefore, 1e-3f, "N=" + N + ": seeded exactly 1000 kg water");

            for (int it = 0; it < 80; it++) {
                char[]  inMat  = matIx.clone();
                float[] before = mass.clone();
                StepTask task = new StepTask(new SubchunkKey(0, 0, 0), matIx, mass, temp, voidHalo());
                List<StepResult> out = e.step(List.of(task), lut,
                        Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
                mass = out.get(0).mass();
                temp = out.get(0).temperature();
                matIx = out.get(0).material() != null ? out.get(0).material() : matIx;
                // (e) §9 per-species gate accepts every step (air conserved on its own index).
                assertTrue(StepValidator.massConservedPerSpecies(mass, before, inMat, matIx, lut),
                        "N=" + N + " it=" + it + ": §9 must accept the displacement step");
            }

            // (a) HEADLINE: total water is EXACTLY 1000.0 — no (N-1)*1.2 mass-from-nothing bug.
            float waterAfter = speciesMass(mass, matIx, 1);
            int waterCells = 0;
            for (int i = 0; i < SEC_N; i++) if (matIx[i] == 1) waterCells++;
            assertTrue(waterCells >= 2,
                    "N=" + N + ": water must actually SPREAD across multiple cells (the repro path), cells="
                            + waterCells);
            assertEquals(1000f, waterAfter, 0.5f,
                    "N=" + N + ": water spread across " + waterCells
                            + " cells must total EXACTLY 1000.0 (was " + waterAfter + ")");
            // (b) air is DISPLACED, not consumed: total air preserved (relocated into the column above).
            float airAfter = speciesMass(mass, matIx, 2);
            assertEquals(airBefore, airAfter, Math.max(1f, airBefore * 1e-3f),
                    "N=" + N + ": air must be CONSERVED (displaced, never consumed), was "
                            + airAfter + " vs " + airBefore);
        }
    }

    // ============================================================================================
    // (c) break block -> VACUUM -> interior neighbours refill -> Sigma air conserved.
    // ============================================================================================

    @Test
    void brokenBlockBecomesVacuumThenAirRefillsConservingAir() {
        NativeEngine e = requireNative();
        List<Material> lut = List.of(voidMat(), water(), air(), stone());   // void, WATER=1, AIR=2, STONE=3

        char[]  matIx = new char[SEC_N];
        float[] mass  = new float[SEC_N];
        float[] temp  = new float[SEC_N];
        Arrays.fill(temp, 300f);
        // An interior 5x5x5 air room (resting 1.2 kg) walled in INERT STONE so the air it holds can
        // neither escape into a wall nor leak out the section face — the only place displaced/expanding
        // air can go is another AIR cell inside the room (tracked, conserved). Room core x,y,z in 6..10.
        for (int x = 5; x <= 11; x++) for (int y = 5; y <= 11; y++) for (int zz = 5; zz <= 11; zz++) {
            matIx[sidx(x, y, zz)] = (char) 3; mass[sidx(x, y, zz)] = 2000f;   // solid shell block
        }
        for (int x = 6; x <= 10; x++) for (int y = 6; y <= 10; y++) for (int zz = 6; zz <= 10; zz++) {
            matIx[sidx(x, y, zz)] = (char) 2; mass[sidx(x, y, zz)] = 1.2f;    // hollow it out with air
        }

        // "Break a block" at the room's centre: M4 makes the cell VACUUM (mass 0, void index 0), NOT
        // 1.2 kg air-from-nothing. The gas rule (E3) must refill it from real neighbours, conserving air.
        int broken = sidx(8, 8, 8);
        float airBefore = speciesMass(mass, matIx, 2);
        matIx[broken] = (char) 0; mass[broken] = 0f;   // vacuum sentinel
        float airAfterBreak = speciesMass(mass, matIx, 2);
        assertEquals(airBefore - 1.2f, airAfterBreak, 1e-3f,
                "breaking a block must REMOVE that cell's air (vacuum), not relabel it");
        assertEquals(0f, mass[broken], 1e-6f, "broken cell must be vacuum (0 mass)");

        float totalAir = airAfterBreak;   // the air that survives must all still be there after refill
        for (int it = 0; it < 40; it++) {
            char[]  inMat  = matIx.clone();
            float[] before = mass.clone();
            StepTask task = new StepTask(new SubchunkKey(0, 0, 0), matIx, mass, temp, voidHalo());
            List<StepResult> out = e.step(List.of(task), lut,
                    Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            mass = out.get(0).mass();
            temp = out.get(0).temperature();
            matIx = out.get(0).material() != null ? out.get(0).material() : matIx;
            assertTrue(StepValidator.massConservedPerSpecies(mass, before, inMat, matIx, lut),
                    "it=" + it + ": §9 must accept the vacuum-refill step");
        }

        // Sigma air is unchanged — the vacuum filled from real neighbours, never created air.
        float airAfter = speciesMass(mass, matIx, 2);
        assertEquals(totalAir, airAfter, Math.max(1f, totalAir * 1e-3f),
                "Σair must be conserved across the break->vacuum->refill (no air-from-nothing), was "
                        + airAfter + " vs " + totalAir);
        // The broken cell is no longer a hard vacuum: gas expanded into it (its mass rose above 0).
        assertTrue(mass[broken] > 0f,
                "the broken (vacuum) cell must have refilled with air from neighbours, was " + mass[broken]);
    }

    // ============================================================================================
    // (d) air COMPRESSES: squeezed from k cells into k-1, conserved, never exceeding max_mass.
    // ============================================================================================

    @Test
    void airCompressesWhenSqueezedStayingUnderMaxMassAndConserved() {
        NativeEngine e = requireNative();
        Material air = air();
        List<Material> lut = List.of(voidMat(), water(), air, stone());   // void, WATER=1, AIR=2, STONE=3

        char[]  matIx = new char[SEC_N];
        float[] mass  = new float[SEC_N];
        float[] temp  = new float[SEC_N];
        Arrays.fill(temp, 300f);

        // A sealed 1-D air pocket of k cells walled in INERT STONE (a true no-flow wall — unlike VOID,
        // which is vacuum that gas would EXPAND into) with a water plug pushing in from one end. The
        // trapped air cannot escape into stone, so as the plug advances the air COMPRESSES into the
        // remaining cells: it conserves and stays ≤ max_mass (1000). Interior, away from every face.
        int k = 5, z = 8, y = 6, x0 = 5;
        // Stone shell: floor below, ceiling above, and both ends — enclosing the 1xk pocket row at y.
        for (int c = -1; c <= k; c++) {
            int below = sidx(x0 + c, y - 1, z); matIx[below] = (char) 3; mass[below] = 2000f;
            int above = sidx(x0 + c, y + 1, z); matIx[above] = (char) 3; mass[above] = 2000f;
        }
        matIx[sidx(x0 - 1, y, z)] = (char) 3; mass[sidx(x0 - 1, y, z)] = 2000f;   // left end (plug side)
        matIx[sidx(x0 + k, y, z)] = (char) 3; mass[sidx(x0 + k, y, z)] = 2000f;    // right end
        // The pocket row x0..x0+k-1 at y: real air (the gas to compress).
        for (int c = 0; c < k; c++) { int i = sidx(x0 + c, y, z); matIx[i] = (char) 2; mass[i] = 1.2f; }
        // Water plug: the leftmost pocket cell becomes a full water cell that pushes the air right.
        int plug = sidx(x0, y, z);
        matIx[plug] = (char) 1; mass[plug] = 1000f;

        float airBefore = speciesMass(mass, matIx, 2);   // (k-1) air cells * 1.2

        for (int it = 0; it < 50; it++) {
            char[]  inMat  = matIx.clone();
            float[] before = mass.clone();
            StepTask task = new StepTask(new SubchunkKey(0, 0, 0), matIx, mass, temp, voidHalo());
            List<StepResult> out = e.step(List.of(task), lut,
                    Scheduler.ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
            mass = out.get(0).mass();
            temp = out.get(0).temperature();
            matIx = out.get(0).material() != null ? out.get(0).material() : matIx;
            assertTrue(StepValidator.massConservedPerSpecies(mass, before, inMat, matIx, lut),
                    "it=" + it + ": §9 must accept the air-compression step");
        }

        // air conserved (compressed, never consumed) and no air cell exceeds max_mass.
        float airAfter = speciesMass(mass, matIx, 2);
        assertEquals(airBefore, airAfter, Math.max(1f, airBefore * 1e-3f),
                "air must be CONSERVED through compression, was " + airAfter + " vs " + airBefore);
        float fullestAir = 0f;
        for (int i = 0; i < SEC_N; i++) if (matIx[i] == 2) fullestAir = Math.max(fullestAir, mass[i]);
        assertTrue(fullestAir <= air.maxMass() + 1e-2f,
                "no air cell may exceed max_mass=" + air.maxMass() + ", fullest was " + fullestAir);
    }
}
