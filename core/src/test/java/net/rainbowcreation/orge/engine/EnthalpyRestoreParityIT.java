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
            float[] cleanT, float[] cleanM, float[] vx, float[] vy, float[] vz, float[] p,
            char[] outMat, char[] inMat) throws Exception {
        Map<Integer, SectionData> column = new TreeMap<>();
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            float[][] tm = ColumnSectionCodec.sliceSection(cleanT, cleanM, sectionY);
            SectionData d = SectionData.full(tm[0], tm[1]);
            System.arraycopy(ColumnSectionCodec.sliceSectionChannel(vx, sectionY), 0, d.velXArray(), 0, SEC);
            System.arraycopy(ColumnSectionCodec.sliceSectionChannel(vy, sectionY), 0, d.velYArray(), 0, SEC);
            System.arraycopy(ColumnSectionCodec.sliceSectionChannel(vz, sectionY), 0, d.velZArray(), 0, SEC);
            System.arraycopy(ColumnSectionCodec.sliceSectionChannel(p, sectionY), 0, d.pArray(), 0, SEC);
            char[] outSec = ColumnSectionCodec.sliceSectionMaterials(outMat, sectionY);
            char[] inSec = ColumnSectionCodec.sliceSectionMaterials(inMat, sectionY);
            for (int i = 0; i < SEC; i++) {
                d.setMaterialAt(i, LUT.get(effectiveSpecies(outSec, inSec, i)).id());
            }
            d.demoteIfUniform();
            column.put(sectionY, d);
        }
        return SectionCodec.readColumn(SectionCodec.writeColumn(column));
    }

    /** Reassembles an engine column from the reloaded store the way the live snapshot path does. */
    private static ColumnTask restoreColumn(NavigableMap<Integer, SectionData> reloaded,
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
                t[i] = d.temperatureAt(i);
                m[i] = d.massAt(i);
                svx[i] = d.velXAt(i);
                svy[i] = d.velYAt(i);
                svz[i] = d.velZAt(i);
                sp[i] = d.pAt(i);
                stored[i] = hasMats ? d.materialAt(i) : null;
            }
            return new ColumnAssembler.SectionCells(firstTouch, m, t, prior, stored, svx, svy, svz, sp);
        };
        return ColumnAssembler.assemble(0, 0, LUT_M, LUT_R, src);
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
                cleanT, cleanM, cleanVx, cleanVy, cleanVz, cleanP, r1.matIx(), seed.matIx());
        ColumnTask taskB = restoreColumn(reloaded, r1.matIx(), seed.matIx());

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
}
