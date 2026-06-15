package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.scheduler.ColumnAssembler;
import net.rainbowcreation.orge.scheduler.ColumnSectionCodec;
import net.rainbowcreation.orge.scheduler.MaterialLut;
import net.rainbowcreation.orge.scheduler.StepValidator;
import net.rainbowcreation.orge.section.SectionCodec;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #10 — Java/JNI enthalpy-carrier interchange contract (law §6/§7).
 *
 * <p>The engine persists ENTHALPY {@code E} per cell; the JNI ABI exchanges TEMPERATURE. On every
 * step the JNI boundary reconstructs {@code E = mass·cp·T} from the {@code (T, mass, cp)} triple
 * Java hands it, and derives {@code T = E/(mass·cp)} back on output. Java's persisted {@code T} is
 * therefore a within-tick INTENSIVE SNAPSHOT, never engine-authoritative state across a step. The
 * contract this test pins: the Java persistence chain (write-back sanitize → {@link SectionData} →
 * {@link SectionCodec} region-blob round-trip → {@link ColumnAssembler} restore) must hand the
 * engine back the bit-identical {@code (T, mass, cp)} triple it produced, so the reconstructed
 * {@code E} — and every derived {@code T} after the next step — is bit-identical to a run that was
 * never saved.</p>
 */
@Tag("integration")
class EnthalpyRestoreParityIT {

    private static final int N = RegionMarshaller.CHUNK_N;
    private static final int SEC = SectionData.CELLS;
    private static final char VOID = 0, WATER = 1, AIR = 2, STONE = 3;
    private static final List<Material> LUT =
            List.of(TestMaterials.voidMat(), TestMaterials.water(), TestMaterials.air(), TestMaterials.stone());
    private static final MaterialLut LUT_M = TestMaterials.lutOf(LUT);
    private static final MaterialRegistry LUT_R = TestMaterials.registryOf(LUT);
    private static final int EPOCH = 41;
    private static final int PASSES = OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION;
    /** Column mass bound for the write-back sanitize (max defaultMass over the column's species). */
    private static final float MASS_BOUND = 2000f;

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    private static int idx(int x, int engineY, int z) {
        return x + 16 * engineY + 6144 * z;
    }

    /**
     * Air-filled column with a stone floor, a warm water stack, and an ambient gradient — both
     * conduction (water 350 K against air/stone 285/300 K) and advection (movable water over the
     * floor) act, so a restore bug in EITHER the temperature or the mass channel shifts the result.
     * Temperatures stay well inside water's [273.15, 373.15] phase band — no relabel noise.
     */
    private static ColumnTask seedColumn() {
        char[] matIx = new char[N];
        float[] mass = new float[N];
        float[] temp = new float[N];
        for (int i = 0; i < N; i++) {
            matIx[i] = AIR;
            mass[i] = 1.2f;
            temp[i] = 285f;
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int i = idx(x, 100, z);
                matIx[i] = STONE;
                mass[i] = 2000f;
                temp[i] = 300f;
            }
        }
        for (int y = 101; y <= 103; y++) {
            int i = idx(8, y, 8);
            matIx[i] = WATER;
            mass[i] = 1000f;
            temp[i] = 350f;
        }
        return new ColumnTask(0, 0, matIx, mass, temp);
    }

    /** The recorded engine-output species per cell (recordCellMaterials' effective-species rule). */
    private static char effectiveSpecies(char[] outMat, char[] inMat, int i) {
        return outMat[i] != 0 ? outMat[i] : inMat[i];
    }

    /**
     * Mirrors {@code MinecraftThermalWorld.writeBackColumn}'s sanitize-then-persist into an in-memory
     * column of {@link SectionData}, then drives the REAL region-blob round-trip
     * ({@link SectionCodec#writeColumn} → bytes → {@link SectionCodec#readColumn}).
     */
    private static NavigableMap<Integer, SectionData> persistAndReload(
            List<Material> lut,
            float[] cleanT, float[] cleanM, float[] vx, float[] vy, float[] vz, float[] p,
            char[] outMat, char[] inMat) throws Exception {
        Map<Integer, SectionData> column = new TreeMap<>();
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            float[][] tm = ColumnSectionCodec.sliceSection(cleanT, cleanM, sectionY);
            SectionData d = SectionData.full(tm[0], tm[1]);
            System.arraycopy(ColumnSectionCodec.sliceSectionChannel(vx, sectionY), 0, d.momXArray(), 0, SEC);
            System.arraycopy(ColumnSectionCodec.sliceSectionChannel(vy, sectionY), 0, d.momYArray(), 0, SEC);
            System.arraycopy(ColumnSectionCodec.sliceSectionChannel(vz, sectionY), 0, d.momZArray(), 0, SEC);
            System.arraycopy(ColumnSectionCodec.sliceSectionChannel(p, sectionY), 0, d.pArray(), 0, SEC);
            char[] outSec = ColumnSectionCodec.sliceSectionMaterials(outMat, sectionY);
            char[] inSec = ColumnSectionCodec.sliceSectionMaterials(inMat, sectionY);
            for (int i = 0; i < SEC; i++) {
                d.setMaterialAt(i, lut.get(effectiveSpecies(outSec, inSec, i)).id());
            }
            d.demoteIfUniform();
            column.put(sectionY, d);
        }
        return SectionCodec.readColumn(SectionCodec.writeColumn(column));
    }

    /** Reassembles an engine column from the reloaded store the way the live snapshot path does. */
    private static ColumnTask restoreColumn(MaterialLut lutM, MaterialRegistry lutR,
                                            NavigableMap<Integer, SectionData> reloaded,
                                            char[] outMat, char[] inMat) {
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            SectionData d = reloaded.get(sectionY);
            char[] outSec = ColumnSectionCodec.sliceSectionMaterials(outMat, sectionY);
            char[] inSec = ColumnSectionCodec.sliceSectionMaterials(inMat, sectionY);
            char[] prior = new char[SEC];
            char[] firstTouch = new char[SEC];
            float[] t = new float[SEC];
            float[] m = new float[SEC];
            float[] svx = new float[SEC];
            float[] svy = new float[SEC];
            float[] svz = new float[SEC];
            float[] sp = new float[SEC];
            Identifier[] stored = new Identifier[SEC];
            boolean hasMats = d.hasMaterials();
            for (int i = 0; i < SEC; i++) {
                char eff = effectiveSpecies(outSec, inSec, i);
                prior[i] = eff;          // last cycle's recorded engine-output species (seed gate)
                firstTouch[i] = eff;     // block first-touch fallback when no stored layer
                t[i] = d.enthalpyAt(i);
                m[i] = d.massAt(i);
                svx[i] = d.momXAt(i);
                svy[i] = d.momYAt(i);
                svz[i] = d.momZAt(i);
                sp[i] = d.pAt(i);
                stored[i] = hasMats ? d.materialAt(i) : null;
            }
            return new ColumnAssembler.SectionCells(firstTouch, m, t, prior, stored, svx, svy, svz, sp);
        };
        return ColumnAssembler.assemble(0, 0, lutM, lutR, src);
    }

    /**
     * Acceptance #1: save → load → step is bit-identical to an uninterrupted run. The restore chain
     * hands back the exact {@code (T, mass)} pair the engine emitted, so the JNI's
     * {@code E = mass·cp·T} reconstruction — and the {@code T = E/(mass·cp)} it derives after the
     * next step — carries no save/load artifact.
     */
    @Test
    void saveLoadStepBitIdenticalToUninterruptedRun() throws Exception {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(EPOCH, LUT);

        ColumnTask seed = seedColumn();
        ColumnResult r1 = engine.stepWorld(List.of(seed), EPOCH, 0.25, PASSES).get(0);

        // Write-back sanitize, exactly as writeBackColumn applies before anything is persisted.
        float[] cleanT = StepValidator.clean(r1.temperature(), seed.temperature());
        float[] cleanM = StepValidator.cleanMass(r1.mass(), MASS_BOUND);
        float[] cleanVx = StepValidator.cleanVelocity(r1.velX(), null);
        float[] cleanVy = StepValidator.cleanVelocity(r1.velY(), null);
        float[] cleanVz = StepValidator.cleanVelocity(r1.velZ(), null);
        float[] cleanP = StepValidator.cleanPressure(r1.p(), null);

        // Path A — uninterrupted: feed the sanitized step-1 state straight back into step 2.
        ColumnTask taskA = new ColumnTask(0, 0, r1.matIx(), cleanM, cleanT,
                cleanVx, cleanVy, cleanVz, cleanP);
        ColumnResult rA = engine.stepWorld(List.of(taskA), EPOCH, 0.25, PASSES).get(0);

        // Path B — save/load: the same sanitized state through the real persistence chain.
        NavigableMap<Integer, SectionData> reloaded = persistAndReload(
                LUT, cleanT, cleanM, cleanVx, cleanVy, cleanVz, cleanP, r1.matIx(), seed.matIx());
        ColumnTask taskB = restoreColumn(LUT_M, LUT_R, reloaded, r1.matIx(), seed.matIx());

        // The restore itself must be lossless — bit-identical (T, mass, cp-index) triple plus the
        // momentum/pressure channels. This is the direct "E reconstructs correctly" check: the JNI
        // rebuilds E from exactly these arrays.
        for (int i = 0; i < N; i++) {
            assertEquals(taskA.matIx()[i], taskB.matIx()[i], "restored matIx[" + i + "]");
            assertEquals(taskA.temperature()[i], taskB.temperature()[i], 0f, "restored T[" + i + "]");
            assertEquals(taskA.mass()[i], taskB.mass()[i], 0f, "restored mass[" + i + "]");
            assertEquals(taskA.velX()[i], taskB.velX()[i], 0f, "restored velX[" + i + "]");
            assertEquals(taskA.velY()[i], taskB.velY()[i], 0f, "restored velY[" + i + "]");
            assertEquals(taskA.velZ()[i], taskB.velZ()[i], 0f, "restored velZ[" + i + "]");
            assertEquals(taskA.p()[i], taskB.p()[i], 0f, "restored p[" + i + "]");
        }

        // And the step over the restored state derives the bit-identical temperature field (golden
        // parity): same E in ⇒ same E out ⇒ same derived T out.
        ColumnResult rB = engine.stepWorld(List.of(taskB), EPOCH, 0.25, PASSES).get(0);
        for (int i = 0; i < N; i++) {
            assertEquals(rA.matIx()[i], rB.matIx()[i], "post-step matIx[" + i + "]");
            assertEquals(rA.temperature()[i], rB.temperature()[i], 0f, "post-step T[" + i + "]");
            assertEquals(rA.mass()[i], rB.mass()[i], 0f, "post-step mass[" + i + "]");
            assertEquals(rA.velX()[i], rB.velX()[i], 0f, "post-step velX[" + i + "]");
            assertEquals(rA.velY()[i], rB.velY()[i], 0f, "post-step velY[" + i + "]");
            assertEquals(rA.velZ()[i], rB.velZ()[i], 0f, "post-step velZ[" + i + "]");
            assertEquals(rA.p()[i], rB.p()[i], 0f, "post-step p[" + i + "]");
        }
    }

    /**
     * Sensitivity guard (proves the parity assertion above has teeth, and pins the snapshot
     * contract): the persisted {@code T} is exactly what seeds the engine's {@code E} on restore —
     * corrupt one stored cell's temperature and the next step's derived temperature field MUST
     * differ. If the engine ever grew a hidden T-authoritative side-channel that survived the JNI
     * boundary (a temp-ghost), this would stop failing the corrupted run and start failing here.
     */
    @Test
    void corruptedRestoredTemperatureChangesTheNextStep() throws Exception {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(EPOCH, LUT);

        ColumnTask seed = seedColumn();
        ColumnResult r1 = engine.stepWorld(List.of(seed), EPOCH, 0.25, PASSES).get(0);

        float[] cleanT = StepValidator.clean(r1.temperature(), seed.temperature());
        float[] cleanM = StepValidator.cleanMass(r1.mass(), MASS_BOUND);
        float[] zero = new float[N];

        ColumnTask intact = new ColumnTask(0, 0, r1.matIx(), cleanM.clone(), cleanT.clone(),
                zero.clone(), zero.clone(), zero.clone(), zero.clone());
        ColumnResult rIntact = engine.stepWorld(List.of(intact), EPOCH, 0.25, PASSES).get(0);

        // Corrupt the water stack's stored temperature by +10 K, as a broken codec/restore would.
        float[] corruptT = cleanT.clone();
        int cell = idx(8, 102, 8);
        corruptT[cell] = corruptT[cell] + 10f;
        ColumnTask corrupt = new ColumnTask(0, 0, r1.matIx(), cleanM.clone(), corruptT,
                zero.clone(), zero.clone(), zero.clone(), zero.clone());
        ColumnResult rCorrupt = engine.stepWorld(List.of(corrupt), EPOCH, 0.25, PASSES).get(0);

        boolean differs = false;
        for (int i = 0; i < N && !differs; i++) {
            differs = rIntact.temperature()[i] != rCorrupt.temperature()[i];
        }
        assertTrue(differs, "a corrupted persisted T must change the engine's derived T "
                + "(T snapshot is what seeds E; nothing else carries thermal state across the step)");
    }

    // ---- T10.9 mid-latent-plateau guard (law §6; v4 §8.1) ------------------------------------------

    private static final char L_VOID = 0, L_WATER = 1, L_STEAM = 2, L_AIR = 3;
    /** Latent-plateau LUT: water boils at 373.15 K (L=2.256e6) → steam (steam present so the engine's
     *  boil-plateau guard fires); air fills the column. Separate from {@link #LUT} so the off-plateau
     *  golden-parity tests above are untouched. */
    private static final List<Material> LAT_LUT = List.of(
            TestMaterials.voidMat(), TestMaterials.waterWithLatent(), TestMaterials.steam(), TestMaterials.air());
    private static final MaterialLut LAT_LUT_M = TestMaterials.lutOf(LAT_LUT);
    private static final MaterialRegistry LAT_LUT_R = TestMaterials.registryOf(LAT_LUT);
    private static final int LAT_EPOCH = 42;

    private static final float WATER_CP = 4186f;      // J/(kg·K)  — TestMaterials.waterWithLatent()
    private static final float BOIL_TSTAR = 373.15f;   // K          — water maxTemp (boil threshold)
    private static final float BOIL_L = 2.256e6f;      // J/kg       — latentHeatMax

    /**
     * T10.9 — mid-latent-plateau round-trip guard (law §6 "T derived from E, E is truth"; v4 §8.1
     * "a mid-plateau cell's T is degenerate over the whole latent band").
     *
     * <p><b>How a genuinely mid-plateau cell is created from Java.</b> The Java↔JNI thermal carrier is
     * TEMPERATURE: {@code NativeEngine.orgeStepWorld} reconstructs the engine's absolute-E channel as
     * {@code Ein = mass·cp·T} (single-slope) from the {@code (T, mass, cp)} triple Java hands it, and on
     * store the JNI derives {@code T_out = E/(mass·cp)} (the legacy single-slope {@code derive_T}, NOT
     * the phase-aware overload — orge_jni.cpp DECODE). For chain-ROOT water (no resolvable colder phase
     * in this LUT) that single slope is EXACTLY the engine's own curve {@code h(T)=cp·T}. A "raw" seed
     * temperature {@code T_seed = T* + Δη/cp} lands the cell's specific enthalpy η at {@code cp·T* + Δη}
     * — strictly INSIDE the boil band {@code [h(T*), h(T*)+L]} when {@code 0 < Δη < L}. Internally the
     * engine PINS the rendered T at T* (mushy), but the value the JNI surfaces across the boundary is
     * the linearized {@code E/(mass·cp)} — here ≈ 642.6 K, NOT 373.15 K.</p>
     *
     * <p><b>KEY OBSERVED RESULT.</b> Because the surfaced {@code T_out = E/(mass·cp)} and the seed
     * {@code Ein = mass·cp·T} are exact inverses, the boundary T-channel is a loss-free <i>encoding</i>
     * of absolute E even for a mid-plateau cell — the persisted T is NOT the degenerate plateau value
     * T*, it is the cell's full latent position re-expressed as a temperature. The disk format
     * (SectionCodec v4) stores that linearized T verbatim, so a world save→load <b>PRESERVES</b> the
     * mid-band E (down to float round-off), it does NOT collapse it to a band edge.</p>
     *
     * <p><b>Keystone (MUST pass):</b> the in-memory round-trip (Path A uninterrupted vs Path B
     * save/load) is bit-identical for the mid-plateau cell — the (T,mass) restore chain hands back the
     * exact triple the engine emitted, so the next step's derived field is identical save-or-no-save.</p>
     *
     * <p><b>Residual gap (characterized).</b> The only mid-plateau loss is the {@code float} precision
     * of the surfaced T (the codec stores {@code float} K, and {@code Ein/Eout} cross as {@code float}).
     * For a 1000 kg cell mid-boil that round-off is ≤ a few kJ — six+ orders below the {@code mass·L ≈
     * 2.26e9 J} that a TRUE T*-collapse would have cost. The latent position survives because the
     * carrier is a linearized-E temperature, not the pinned render T. The disk-format decision (keep v4
     * float-K, or bump SectionCodec v5 to persist double absolute E for exactness) is the Subtask-9
     * USER ESCALATION — NOT decided here (v4 §8.1).</p>
     */
    @Test
    void midPlateauCellRoundTrip() throws Exception {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(LAT_EPOCH, LAT_LUT);

        // Seed: an air column, one water cell driven MID-BOIL-BAND. The raw seed T places η at the band
        // midpoint (Δη = L/2) via the single-slope Ein = mass·cp·T reconstruction.
        final int cell = idx(8, 102, 8);
        final float mass = 1000f;
        final float midDeltaEta = BOIL_L / 2f;                      // J/kg into the latent band
        final float rawSeedT = BOIL_TSTAR + midDeltaEta / WATER_CP; // T s.t. cp·T = cp·T* + L/2 (≈ 642.6 K)
        char[] matIx = new char[N];
        float[] m = new float[N];
        float[] t = new float[N];
        for (int i = 0; i < N; i++) { matIx[i] = L_AIR; m[i] = 1.2f; t[i] = 285f; }
        matIx[cell] = L_WATER; m[cell] = mass; t[cell] = rawSeedT;
        ColumnTask seed = new ColumnTask(0, 0, matIx, m, t);

        // Step 1: the engine ingests Ein = mass·cp·rawSeedT (specific enthalpy mid-boil-band). The cell
        // is genuinely mid-plateau: η ∈ (h(T*), h(T*)+L). It STAYS water (no relabel until η > band top)
        // and the JNI surfaces the linearized T = E/(mass·cp) ≈ rawSeedT — NOT the pinned render T*.
        ColumnResult r1 = engine.stepWorld(List.of(seed), LAT_EPOCH, 0.25, PASSES).get(0);

        assertEquals(L_WATER, r1.matIx()[cell],
                "a cell mid-band (η < h(T*)+L) must STAY water — no relabel until past the boil band");
        // Mid-band ⇒ the surfaced (linearized) T is ABOVE T*: the latent position is carried in T, not
        // collapsed to the plateau threshold. (If the JNI ever surfaced the pinned T*, this would drop
        // to 373.15 and the latent energy would be lost across the T carrier — the gap this guards.)
        assertTrue(r1.temperature()[cell] > BOIL_TSTAR + 1f,
                "the JNI surfaces the linearized E/(mass·cp) temperature (≈642.6 K), which carries the "
                        + "mid-band latent position — NOT the degenerate pinned plateau T* (v4 §8.1)");

        // Sanitize step-1 state exactly as the live write-back does.
        float[] cleanT = StepValidator.clean(r1.temperature(), seed.temperature());
        float[] cleanM = StepValidator.cleanMass(r1.mass(), MASS_BOUND);
        float[] cleanVx = StepValidator.cleanVelocity(r1.velX(), null);
        float[] cleanVy = StepValidator.cleanVelocity(r1.velY(), null);
        float[] cleanVz = StepValidator.cleanVelocity(r1.velZ(), null);
        float[] cleanP = StepValidator.cleanPressure(r1.p(), null);

        // ---- KEYSTONE: in-memory save/load round-trip is loss-free even mid-plateau ----------------
        // Path A — uninterrupted: feed the sanitized step-1 state straight back into step 2.
        ColumnTask taskA = new ColumnTask(0, 0, r1.matIx(), cleanM, cleanT,
                cleanVx, cleanVy, cleanVz, cleanP);
        ColumnResult rA = engine.stepWorld(List.of(taskA), LAT_EPOCH, 0.25, PASSES).get(0);

        // Path B — save/load: the same sanitized state through the real SectionCodec region-blob chain.
        NavigableMap<Integer, SectionData> reloaded = persistAndReload(
                LAT_LUT, cleanT, cleanM, cleanVx, cleanVy, cleanVz, cleanP, r1.matIx(), seed.matIx());
        ColumnTask taskB = restoreColumn(LAT_LUT_M, LAT_LUT_R, reloaded, r1.matIx(), seed.matIx());

        for (int i = 0; i < N; i++) {
            assertEquals(taskA.matIx()[i], taskB.matIx()[i], "restored matIx[" + i + "]");
            assertEquals(taskA.temperature()[i], taskB.temperature()[i], 0f, "restored T[" + i + "]");
            assertEquals(taskA.mass()[i], taskB.mass()[i], 0f, "restored mass[" + i + "]");
        }
        ColumnResult rB = engine.stepWorld(List.of(taskB), LAT_EPOCH, 0.25, PASSES).get(0);
        for (int i = 0; i < N; i++) {
            assertEquals(rA.matIx()[i], rB.matIx()[i], "post-step matIx[" + i + "]");
            assertEquals(rA.temperature()[i], rB.temperature()[i], 0f, "post-step T[" + i + "]");
            assertEquals(rA.mass()[i], rB.mass()[i], 0f, "post-step mass[" + i + "]");
        }

        // ---- DISK PATH: it PRESERVES the mid-band E (does NOT collapse to a band edge) -------------
        // The disk stores the surfaced LINEARIZED T (E/(mass·cp)), NOT the pinned plateau T*. So the E
        // reconstructed on reload is the same mid-band E (up to float round-off), NOT the band bottom.
        // engineY = sectionY*16 + sy + 64 (ColumnSectionCodec): y=102 ⇒ sectionY=2, sy=6;
        // si = x + 16*sy + 256*z.
        final int sectionY = (102 - 64) / 16;        // = 2
        final int sy = 102 - 64 - sectionY * 16;     // = 6
        final int si = 8 + 16 * sy + 256 * 8;        // x=8, z=8
        float storedT = reloaded.get(sectionY).enthalpyAt(si);
        float storedM = reloaded.get(sectionY).massAt(si);
        assertTrue(storedT > BOIL_TSTAR + 1f,
                "the disk stores the linearized E-encoding T (≈642.6 K), not the pinned plateau T* — "
                        + "so it does NOT collapse the latent position (this is the observed behavior)");

        // E preserved across the disk save = mass·cp·storedT (the JNI's Ein reconstruction on reload),
        // compared to the cell's actual mid-band E = mass·cp·(surfaced T). The only loss is float K
        // round-off — orders of magnitude below the mass·L a true T*-collapse would have cost.
        double eOnReload = (double) storedM * WATER_CP * storedT;
        double eBeforeSave = (double) cleanM[cell] * WATER_CP * cleanT[cell];
        double eIfCollapsedToTStar = (double) storedM * WATER_CP * BOIL_TSTAR; // the band-bottom E
        double diskLoss = Math.abs(eBeforeSave - eOnReload);
        double collapseLossWouldBe = Math.abs(eBeforeSave - eIfCollapsedToTStar);

        assertEquals(eBeforeSave, eOnReload, 1e-3 * eBeforeSave,
                "the disk (T-only, but the surfaced T is the linearized E encoding) PRESERVES mid-band "
                        + "E to float precision (v4 §8.1; Subtask-9 escalation: keep v4 vs bump v5 for exact E)");
        // Sanity: a genuine T*-collapse WOULD have been catastrophic (mass·Δη scale) — proving the
        // observed preservation is meaningful, not a band-bottom cell trivially matching itself.
        assertTrue(collapseLossWouldBe > 1e8 && diskLoss < 1e6,
                "a hypothetical pinned-T* persistence would lose ~mass·Δη (>1e8 J); the actual disk loss "
                        + "is float round-off (<1e6 J) — the latent position survives the save");
    }
}
