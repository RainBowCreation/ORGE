package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialTable;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static net.rainbowcreation.orge.scheduler.SkyTestWorld.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #80: one owner of the surface face's evaporation. One combined cycle — the sky pass on the step
 * thread ({@link MinecraftThermalWorld#forceCaptured}) and then the native engine step over the column it
 * forced — evaporates a daytime water surface under dry air at the SINGLE Penman flux
 * {@code χ_pair·g_vap·A·dt·p_sat(T)} per surface cell, not twice it, with every species' mass and the grand
 * energy balanced against the sky pass's boundary ledgers.
 */
@Tag("integration")
class EvaporationIT {

    private static final Identifier STEAM = orge("steam");
    private static final float T0 = 300f;
    private static final double DT = 1.0;
    private static final SurfaceForcing.Climate NOON =
            SurfaceForcing.Climate.forDimension(OVERWORLD, 6000L, 0f).orElseThrow();

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    /** Column (0,0) as the snapshot captures it, headless: each section's store view. */
    private static CapturedColumn capture(SectionStore store) {
        CapturedColumn.Section[] secs =
                new CapturedColumn.Section[ColumnAssembler.MAX_SECTION_Y - ColumnAssembler.MIN_SECTION_Y + 1];
        for (int sy = ColumnAssembler.MIN_SECTION_Y; sy <= ColumnAssembler.MAX_SECTION_Y; sy++) {
            SubchunkKey key = new SubchunkKey(0, sy, 0);
            secs[sy - ColumnAssembler.MIN_SECTION_Y] =
                    new CapturedColumn.Section(null, T0, null, store.get(key).share(), store.hasSection(key));
        }
        return new CapturedColumn(OVERWORLD, 0, 0, secs, List.of());
    }

    /** The assembler's view of a captured, fully stored column: every channel straight from the sections. */
    private static ColumnAssembler.SectionSource source(CapturedColumn c, MaterialLut lut) {
        return (cx, cz, sectionY) -> {
            SectionData d = c.section(sectionY).data();
            int n = SectionData.CELLS;
            char[] mat = new char[n];
            float[] mass = new float[n];
            float[] temp = new float[n];
            Identifier[] stored = new Identifier[n];
            float[] px = new float[n], py = new float[n], pz = new float[n], p = new float[n], sr = new float[n];
            double[] e = new double[n];
            Identifier[] slotMat = new Identifier[SectionData.SLOT_CELLS];
            float[] slotMass = new float[SectionData.SLOT_CELLS];
            for (int i = 0; i < n; i++) {
                stored[i] = d.materialAt(i);
                mat[i] = lut.indexOf(stored[i]);
                mass[i] = d.massAt(i);
                temp[i] = (float) CellTemperature.derived(d, i, lookup());
                px[i] = d.momXAt(i);
                py[i] = d.momYAt(i);
                pz[i] = d.momZAt(i);
                p[i] = d.pAt(i);
                sr[i] = d.swapReadyAt(i);
                e[i] = d.enthalpyAt(i);
                for (int k = 1; k < SectionData.SLOTS; k++) {
                    slotMat[(k - 1) * n + i] = d.slotMaterialAt(k, i);
                    slotMass[(k - 1) * n + i] = d.slotMassAt(k, i);
                }
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, new char[n], stored,
                    px, py, pz, p, sr, e, slotMat, slotMass);
        };
    }

    /** Exact Σ mass [kg] of LUT index {@code ix} over an engine column, every slot. */
    private static BigDecimal massOf(char[] matIx, float[] mass, char[] slotIx, float[] slotMass, char ix) {
        BigDecimal sum = BigDecimal.ZERO;
        int n = RegionMarshaller.CHUNK_N;
        for (int i = 0; i < n; i++) {
            if (matIx[i] == ix && mass[i] > 0f) sum = sum.add(new BigDecimal(mass[i]));
        }
        for (int j = 0; j < slotIx.length; j++) {
            if (slotIx[j] == ix && slotMass[j] > 0f) sum = sum.add(new BigDecimal(slotMass[j]));
        }
        return sum;
    }

    private static BigDecimal massOf(ColumnTask t, char ix) {
        return massOf(t.matIx(), t.mass(), t.slotIx(), t.slotMass(), ix);
    }

    private static BigDecimal massOf(ColumnResult r, char ix) {
        return massOf(r.matIx(), r.mass(), r.slotIx(), r.slotMass(), ix);
    }

    private static BigDecimal sum(double[] e) {
        BigDecimal s = BigDecimal.ZERO;
        for (double v : e) s = s.add(new BigDecimal(v));
        return s;
    }

    /** χ = (max − default)/(max − min + ε), clamped: the compressibility weight the engine computes. */
    private static double chi(Material m) {
        float x = (m.maxMass() - m.defaultMass()) / (m.maxMass() - m.minMass() + 1e-6f);
        return Math.max(0f, Math.min(1f, x));
    }

    @Test
    void oneDaytimeCycleEvaporatesTheSurfaceAtASinglePenmanFlux(@TempDir Path dir) {
        NativeEngine engine = engineOrSkip();
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        // A full-height column: stone, a full water layer at section 4's local y 8, dry air to the world top.
        for (int sy = ColumnAssembler.MIN_SECTION_Y; sy <= ColumnAssembler.MAX_SECTION_Y; sy++) {
            int s = sy;
            putSection(store, sy, y -> s < 4 ? STONE : s > 4 ? AIR : y < 8 ? STONE : y == 8 ? WATER : AIR, T0);
        }
        List<Material> ordered = MaterialTable.ordered(registry());
        MaterialLut lut = new MaterialLut(ordered, MaterialTable.slots(ordered));
        char water = lut.indexOf(WATER);
        char steam = lut.indexOf(STEAM);
        char air = lut.indexOf(AIR);
        char stone = lut.indexOf(STONE);
        ColumnTask before = ColumnAssembler.assemble(0, 0, lut, registry(), source(capture(store), lut));

        // One cycle: the sky pass over the capture (forcing, rain, evaporation), then one engine step.
        CapturedColumn c = capture(store);
        MinecraftThermalWorld.SkyLedgers sky = MinecraftThermalWorld.forceCaptured(c,
                new MinecraftThermalWorld.SkyPass(NOON, new Evaporation().nextDither(OVERWORLD, NOON, lookup())),
                DT, lookup());
        ColumnTask task = ColumnAssembler.assemble(0, 0, lut, registry(), source(c, lut));
        engine.registerMaterials(1, ordered);
        ColumnResult r = engine.stepWorld(List.of(task), 1, DT,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);

        Material w = mat(WATER);
        Material h = mat(STEAM);
        double single = 256 * Math.max(chi(w), chi(h)) * Evaporation.G_VAP_PENMAN * 1.0 * DT
                * Evaporation.pSat(w, h, T0);
        double moved = massOf(r, steam).doubleValue();
        assertTrue(moved > 0.0, "NON-VACUOUS: the surface evaporated");
        assertEquals(single, moved, 0.25 * single,
                "one cycle moves ONE Penman flux off the 256 surface cells (" + single + " kg), not two: " + moved);

        // H₂O balances exactly against the sky pass's boundary ledger; the other species and the grand E to the
        // engine's float32 rounding.
        BigDecimal h2o0 = massOf(before, water).add(massOf(before, steam));
        BigDecimal h2o1 = massOf(r, water).add(massOf(r, steam));
        assertEquals(0, h2o1.subtract(h2o0).compareTo(new BigDecimal(sky.evaporation().mass(OVERWORLD))),
                "Σ (water + steam) change " + h2o1.subtract(h2o0) + " = the evaporation sink "
                        + sky.evaporation().mass(OVERWORLD));
        double air0 = massOf(before, air).doubleValue();
        double stone0 = massOf(before, stone).doubleValue();
        assertEquals(air0, massOf(r, air).doubleValue(), 1e-9 * air0, "air mass unchanged (engine float32 books)");
        assertEquals(stone0, massOf(r, stone).doubleValue(), 1e-9 * stone0, "stone mass unchanged");
        BigDecimal ledgers = new BigDecimal(sky.forcing().ledger(OVERWORLD))
                .add(new BigDecimal(sky.evaporation().energy(OVERWORLD)));
        BigDecimal e0 = sum(before.enthalpy());
        double drift = sum(r.enthalpy()).subtract(e0).subtract(ledgers).doubleValue();
        assertEquals(0.0, drift, 1e-9 * e0.abs().doubleValue(),
                "Σ E change = forcing + evaporation energy ledgers (to the engine's float32 books, as JniSeamEnergyIT)");
    }
}
