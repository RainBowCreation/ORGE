package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.RegionStepResult;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.rainbowcreation.orge.scheduler.SkyTestWorld.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #95: the §9 region gate must conserve a substance across ALL its phases, immovable partners
 * included. Through the bundled liborge and the shipped material JSON, a REAL {@code stepWorld} moves
 * the lever-rule share of a mixed cell into its immovable partner's slot (water → ice on a lake
 * surface freezing under cold air; lava → basalt where lava meets water); every step is gated by the
 * same {@link StepValidator.SpeciesMassLedger} calls the Scheduler makes ({@code add} + {@code addSlots} +
 * {@code expect}). Before the fix the partial share read as water / lava destroyed and the region HELD.
 */
@Tag("integration")
class ImmovablePartnerPhaseTransferIT {

    private static final Identifier LAVA = orge("lava");
    private static final Identifier BASALT = orge("basalt");
    private static final Identifier ICE = orge("ice");
    private static final double DT = 0.25;
    private static final int N = RegionMarshaller.CHUNK_N;
    private static final int FY = 100, WT = FY + 3, AIR_TOP = WT + 4;
    private static final int X0 = 4, X1 = 10, Z0 = 4, Z1 = 10;

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    private static double massOf(char[] matIx, float[] mass, char[] slotIx, float[] slotMass, char ix) {
        double s = 0;
        for (int i = 0; i < N; i++) if (matIx[i] == ix) s += mass[i];
        for (int j = 0; j < slotIx.length; j++) if (slotIx[j] == ix) s += slotMass[j];
        return s;
    }

    /** Mass of {@code ix} sitting in composition slots 1..K−1 only (a partial share, not a relabelled cell). */
    private static double slotMassOf(char[] slotIx, float[] slotMass, char ix) {
        double s = 0;
        for (int j = 0; j < slotIx.length; j++) if (slotIx[j] == ix) s += slotMass[j];
        return s;
    }

    private record Run(int heldSteps, double peakPartnerSlotMass) {}

    /**
     * A still 7×7×3 stone basin (cells above the floor are {@code body}, air above), with the surface layer
     * (row WT) mixed: each cell {@code body} primary + a little gas in slot 1, whose single E has paid {@code frac}
     * of the {@code bandT} phase band — so the engine's per-component phase transfer moves that lever
     * share of the primary into {@code partner}'s slot. Every step is gated like the Scheduler.
     */
    private static Run run(Identifier body, Identifier gas, Identifier partner, float bandT, float latent,
                           double frac, float bodyT, int steps) {
        NativeEngine engine = engineOrSkip();
        List<Material> ordered = MaterialTable.ordered(registry());
        MaterialLut lut = new MaterialLut(ordered, MaterialTable.slots(ordered));
        char partnerIx = lut.indexOf(partner);
        engine.registerMaterials(1, ordered);

        char[] mat = new char[N];
        float[] mass = new float[N], t = new float[N];
        double[] e = new double[N];
        for (int x = X0 - 1; x <= X1 + 1; x++) for (int z = Z0 - 1; z <= Z1 + 1; z++) for (int y = FY; y <= AIR_TOP; y++) {
            boolean wall = y == FY || x < X0 || x > X1 || z < Z0 || z > Z1;
            Identifier id = wall ? STONE : y <= WT ? body : AIR;
            int i = RegionMarshaller.colIdx(x, y, z);
            mat[i] = lut.indexOf(id);
            mass[i] = mat(id).defaultMass();
            t[i] = id.equals(AIR) ? 290f : bodyT;
        }
        for (int i = 0; i < N; i++)
            if (mass[i] > 0f) e[i] = EnthalpyCurve.cellE(mass[i], ordered.get(mat[i]), lookup(), t[i]);

        char[] slotIx = new char[RegionMarshaller.SLOT_N];
        float[] slotMass = new float[RegionMarshaller.SLOT_N];
        float gasKg = mat(gas).defaultMass();
        for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) {   // the whole surface layer is mixed
            int mixed = RegionMarshaller.colIdx(x, WT, z);
            slotIx[mixed] = lut.indexOf(gas);                              // slot 1 of the mixed cell
            slotMass[mixed] = gasKg;
            e[mixed] = EnthalpyCurve.cellE(mass[mixed], ordered.get(mat[mixed]), lookup(), bandT)
                    + EnthalpyCurve.cellE(gasKg, mat(gas), lookup(), bandT)
                    - frac * mass[mixed] * latent;
            t[mixed] = bandT;
        }

        float[] px = new float[N], py = new float[N], pz = new float[N], p = new float[N], sr = new float[N];
        int held = 0;
        double peak = 0;
        for (int n = 0; n < steps; n++) {
            ColumnTask task = new ColumnTask(0, 0, mat, mass, t, px, py, pz, p, sr, e, slotIx, slotMass);
            RegionStepResult res = engine.stepWorld(List.of(task), 1, DT,
                    OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION, List.of());
            ColumnResult r = res.columns().get(0);
            StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
            ledger.add(r.mass(), task.mass(), task.matIx(), r.matIx(), ordered);
            ledger.addSlots(r.slotMass(), task.slotMass(), task.slotIx(), r.slotIx(), ordered);
            ledger.expect(res.injected(), res.sealedLoss());
            if (!ledger.conserved()) held++;
            peak = Math.max(peak, slotMassOf(r.slotIx(), r.slotMass(), partnerIx));
            mat = r.matIx(); mass = r.mass(); t = r.temperature(); e = r.enthalpy();
            px = r.momX(); py = r.momY(); pz = r.momZ(); p = r.p(); sr = r.swapReady();
            slotIx = r.slotIx(); slotMass = r.slotMass();
        }
        return new Run(held, peak);
    }

    /** ε·N for one column is ~983 kg: the shares the engine moves must exceed it for the old gate to HOLD. */
    private static final double TOL_KG = StepValidator.MASS_EPSILON_PER_CELL * (double) N;

    /** Water cells whose one E has paid 80% of the freeze band: the share crosses into an ice slot. */
    @Test
    void waterCellCrossingTheFreezeBandPartiallyConservesWaterAndIce() {
        Run r = run(WATER, AIR, ICE, 273.15f, 3.34e5f, 0.8, 273.2f, 8);
        assertTrue(r.peakPartnerSlotMass() > TOL_KG,
                "the engine moved a share larger than ε·N into the ice slot (peak " + r.peakPartnerSlotMass() + " kg)");
        assertEquals(0, r.heldSteps(), "the §9 ledger conserves the water+ice family: no step HOLDs");
    }

    /** Lava cells whose one E has paid 25% of the solidify band: a partial share lands in a basalt slot. */
    @Test
    void lavaCellCrossingTheSolidifyBandPartiallyConservesLavaAndBasalt() {
        Run r = run(LAVA, AIR, BASALT, 1398f, 4e5f, 0.25, 1398f, 8);
        assertTrue(r.peakPartnerSlotMass() > TOL_KG,
                "the engine moved a share larger than ε·N into the basalt slot (peak " + r.peakPartnerSlotMass() + " kg)");
        assertEquals(0, r.heldSteps(), "the §9 ledger conserves the lava+basalt family: no step HOLDs");
    }
}
