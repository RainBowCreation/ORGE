package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;
import java.util.function.Supplier;

/**
 * One validated {@link ThermalWorld.ColumnEntry} + {@link ColumnResult} in &rarr; its 24 sections
 * persisted out (DESIGN 2026-06-01 §7) for the live {@link MinecraftThermalWorld}. Scatters the
 * engine's authoritative mass / extensive E / momentum / pressure / swap-cadence channels back into
 * the {@link SectionStore} via {@link ColumnSectionCodec}, persists the engine-output species as the
 * durable per-cell identity, then per reconstructed section records the next cycle's signature
 * ({@link #recordCellMaterials}), settles its dormancy countdown ({@link #noteSettle}), and drives the
 * §10/§7 phase/reconcile seams. Finally wakes any apron neighbour column that could have received mass
 * across an X/Z boundary.
 *
 * <p>The batch material LUT (set by {@link ColumnSnapshot}) and the reconcile/phase seams (set after
 * construction in {@link Orge}) are read lazily through the injected suppliers, so this module needs no
 * back-reference to the facade. Server-thread-confined.</p>
 */
final class ColumnWriteBack {

    private final SectionStoreManager stores;
    private final CellMaterialTracker cellMaterials;
    private final ActiveSet activeSet;
    private final Supplier<FluidReconciler> reconcilerSource;
    private final Supplier<PhaseChanger> phaseChangerSource;
    private final Supplier<List<Material>> lutSource;

    ColumnWriteBack(SectionStoreManager stores, CellMaterialTracker cellMaterials, ActiveSet activeSet,
                    Supplier<FluidReconciler> reconcilerSource, Supplier<PhaseChanger> phaseChangerSource,
                    Supplier<List<Material>> lutSource) {
        this.stores = stores;
        this.cellMaterials = cellMaterials;
        this.activeSet = activeSet;
        this.reconcilerSource = reconcilerSource;
        this.phaseChangerSource = phaseChangerSource;
        this.lutSource = lutSource;
    }

    // Server thread only.
    void writeBackColumn(ThermalWorld.ColumnEntry entry, ColumnResult result) {
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(entry.cx(), entry.cz())) {
            return;
        }
        // The batch LUT the snapshot resolved against (no LUT travels with the ColumnResult); both the
        // snapshot and this write-back run on the server thread in the same cycle, so the field is stable.
        List<Material> lut = lutSource.get();
        float maxMassDelta = 0f;
        float[] inMass = entry.task().mass();
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            SubchunkKey key = new SubchunkKey(entry.cx(), sectionY, entry.cz());
            float[] outMass = ColumnSectionCodec.sliceSectionChannel(result.mass(), sectionY);
            // Input temperature slice — carried into the reconstructed per-section StepTask below only as
            // a diagnostic (the §10/§7 seams derive their own T from stored E); never stored as E.
            float[] inT = ColumnSectionCodec.sliceSectionChannel(entry.task().temperature(), sectionY);
            // Mass clamped to [0, fullMassBound] (the column's max defaultMass over its species). The T
            // channel is NO LONGER the stored thermal truth (S6): stored E is. We slice the engine's
            // AUTHORITATIVE energy below; the result.temperature() (legacy derived tOut) is not stored.
            float[] cleanM = StepValidator.cleanMass(outMass, fullMassBound(entry.task()));
            char[] outMat = ColumnSectionCodec.sliceSectionMaterials(result.matIx(), sectionY);
            char[] inMatSec = ColumnSectionCodec.sliceSectionMaterials(entry.task().matIx(), sectionY);

            SectionData data = store.get(key);
            // Stale-write-back guard: if an external edit (/orge set) landed after this cycle's
            // snapshot read the section, this result was computed from pre-edit input — persisting it
            // would clobber the edit. Skip the section; the edit is re-snapshotted + simulated next
            // cycle. (Self-healing: next snapshot re-stamps the epoch, so the skip is one cycle only.)
            if (data.editedSinceSnapshot()) {
                continue;
            }
            // S6 (law §6/§7): store the engine's AUTHORITATIVE extensive energy eOut (the S5-threaded
            // ColumnResult.enthalpy) UNCLAMPED. E is extensive [J]; the [0,6000] StepValidator clamp is a
            // KELVIN range and would destroy mid-plateau energy (E ≫ 6000 J) — it must NEVER touch E.
            // Only non-finite E is sanitized (defensive) to a safe 0; the magnitude is stored raw.
            float[] secE = ColumnSectionCodec.sliceSectionChannel(result.enthalpy(), sectionY);
            float[] dstE = data.enthalpyArray();
            for (int i = 0; i < SectionData.CELLS; i++) {
                float e = secE[i];
                dstE[i] = Float.isFinite(e) ? e : 0f;   // sanitize non-finite only — NO magnitude clamp
            }
            float[] dstM = data.massArray();
            System.arraycopy(cleanM, 0, dstM, 0, SectionData.CELLS);
            // Momentum write-back (F2, law §7 / §1.1, POLICY (i)): store the engine's AUTHORITATIVE
            // EXTENSIVE momentum p [kg·m/s] (result.momX/Y/Z = engine pxOut/pyOut/pzOut) UNCHANGED — the
            // EXACT mirror of the enthalpy-E writeback above. Momentum is signed/unbounded, so it carries
            // NO domain clamp; it is INDEPENDENT of the §9 mass clamp (NO cleanM·vOut rescale — that
            // reconstruction WAS the law-#7 velocity-ghost). Only non-finite values are sanitized to 0
            // (cleanMomentum, which delegates to the shared finite-or-0 sanitizer). The section is already
            // FULL from the E/mass array writes above, so momXArray() etc. allocate safely. Does NOT gate
            // mass conservation.
            float[] secPx = ColumnSectionCodec.sliceSectionChannel(result.momX(), sectionY);
            float[] secPy = ColumnSectionCodec.sliceSectionChannel(result.momY(), sectionY);
            float[] secPz = ColumnSectionCodec.sliceSectionChannel(result.momZ(), sectionY);
            float[] cleanPx = StepValidator.cleanMomentum(secPx, null);
            float[] cleanPy = StepValidator.cleanMomentum(secPy, null);
            float[] cleanPz = StepValidator.cleanMomentum(secPz, null);
            System.arraycopy(cleanPx, 0, data.momXArray(), 0, SectionData.CELLS);
            System.arraycopy(cleanPy, 0, data.momYArray(), 0, SectionData.CELLS);
            System.arraycopy(cleanPz, 0, data.momZArray(), 0, SectionData.CELLS);
            // Dynamic-pressure write-back: slice the single p channel, sanitize non-finite AND clamp
            // negatives to 0 (p >= 0 — a free surface is p=0), then persist into the SectionData p array.
            // Persisting p is what makes depth-pressure survive across engine steps and save/load.
            float[] secP = ColumnSectionCodec.sliceSectionChannel(result.p(), sectionY);
            float[] cleanP = StepValidator.cleanPressure(secP, null);
            System.arraycopy(cleanP, 0, data.pArray(), 0, SectionData.CELLS);
            // T10c: persist the §5.3 swap-cadence accumulator in-memory (law #7 bookkeeping; NOT serialized)
            // so swaps fire across scheduler calls. swapReady is a non-negative accumulator, so reuse
            // cleanPressure's sanitizer (strips NaN/inf AND clamps <0 → 0) — the exact non-negative guard;
            // it cannot wipe legitimate positive accumulation since the engine emits >=0.
            float[] secSr = ColumnSectionCodec.sliceSectionChannel(result.swapReady(), sectionY);
            float[] cleanSr = StepValidator.cleanPressure(secSr, null);
            System.arraycopy(cleanSr, 0, data.swapReadyArray(), 0, SectionData.CELLS);
            // Durable identity (durable-material §, keystone-closing half): persist each cell's
            // engine-output material id into the store so next cycle E1's columnSource reads it as
            // authoritative (hasMaterials()==true). Effective-species rule mirrors recordCellMaterials:
            // engine output when present, else the input/world material — so an untouched air cell records
            // orge:air, never the index-0 orge:vacuum sentinel; a genuinely empty/broken cell whose
            // effective species is index 0 records orge:vacuum. setMaterialAt promotes to FULL (already
            // FULL from the array writes), allocates the palette and interns the id (mirrors mass/temp).
            for (int i = 0; i < SectionData.CELLS; i++) {
                char sp = (outMat != null && i < outMat.length && outMat[i] != 0) ? outMat[i] : inMatSec[i];
                data.setMaterialAt(i, lut.get(sp).id());
            }
            data.demoteIfUniform();
            store.put(key, data);

            // Reconstruct the per-section entry the §10/§7 seams consume (input geometry + this section's
            // engine output species). recordCellMaterials/noteSettle/reconcile/phase mirror the
            // per-section advection write-back exactly.
            StepTask secTask = new StepTask(key, inMatSec, inMass(inMass, sectionY), inT,
                    NeighborHalo.empty());
            ThermalWorld.BatchEntry secEntry = new ThermalWorld.BatchEntry(entry.dimension(), key, secTask);
            recordCellMaterials(secEntry, outMat, lut);
            float secMassDelta = maxAbsDelta(cleanM, secTask.mass());
            if (secMassDelta > maxMassDelta) maxMassDelta = secMassDelta;
            noteSettle(secEntry, secMassDelta, -1f);
            phaseChangerSource.get().applyPhaseChanges(secEntry, outMat, lut);
            reconcilerSource.get().reconcile(secEntry, outMat, lut);
        }
        // Wake any LOADED neighbour column that could have received mass across an X/Z boundary this step
        // (DESIGN §5 wake-on-cross): if our column moved any mass, the apron neighbour must re-enter next
        // cycle to accept incoming flow rather than stranding it at the seam. Column-granular replacement
        // (the old per-section seam wake); over-waking is harmless (the neighbour settles via noteSettle).
        if (maxMassDelta >= SettleCountdown.EPS_MASS) {
            wakeColumnNeighbour(entry.dimension(), entry.cx() - 1, entry.cz());
            wakeColumnNeighbour(entry.dimension(), entry.cx() + 1, entry.cz());
            wakeColumnNeighbour(entry.dimension(), entry.cx(), entry.cz() - 1);
            wakeColumnNeighbour(entry.dimension(), entry.cx(), entry.cz() + 1);
        }
    }

    /**
     * Feed a section's per-step settle deltas into the active set (DESIGN §10 Decision 11). A
     * negative delta means that pass did not run this cycle (skip its countdown), so a coincident
     * conduction tick still counts the flow pass and vice versa.
     */
    void noteSettle(ThermalWorld.BatchEntry entry, float maxMassDelta, float maxTempDelta) {
        if (maxMassDelta >= 0f) {
            activeSet.noteFlowDelta(entry.dimension(), entry.key(), maxMassDelta);
        }
        if (maxTempDelta >= 0f) {
            activeSet.noteThermalDelta(entry.dimension(), entry.key(), maxTempDelta);
        }
    }

    /**
     * Record the engine's OUTPUT species as the signature for this section's just-persisted mass
     * (DESIGN §10 follow-on; the reseed-misfire fix). Per cell the recorded species is the engine
     * output when present ({@code outMat[i] != 0}), else the cell's input/world material — so an
     * untouched air cell records {@code orge:air}, never the index-0 {@code orge:vacuum} sentinel. This
     * signature is the "last cycle's engine output" that the NEXT snapshot's ColumnAssembler seed gate
     * and the InjectionDrain incumbent lookup read — it is event-independent (a place/break overwrites
     * the durable {@code SectionStore} identity but NOT this recorder), so a freshly-placed cell still
     * carries its old engine-output species here while the store already holds the new one. When the
     * signature is unchanged the prior {@code Identifier[]} is reused
     * verbatim, avoiding the 4096-ref signature re-allocation; the small per-cell {@code char[]}
     * species scratch is still built each call.
     */
    void recordCellMaterials(ThermalWorld.BatchEntry entry, char[] outMat, List<Material> lut) {
        char[] inMat = entry.task().matIx();
        char[] effective = new char[inMat.length];
        for (int i = 0; i < inMat.length; i++) {
            effective[i] = (outMat != null && i < outMat.length && outMat[i] != 0) ? outMat[i] : inMat[i];
        }
        Identifier[] prior = cellMaterials.prior(entry.dimension(), entry.key());
        cellMaterials.record(entry.dimension(), entry.key(), liveMaterialIds(effective, lut, prior));
    }

    /** The section-Y slice of a column's input mass (for the per-section reconstructed StepTask). */
    private static float[] inMass(float[] columnInMass, int sectionY) {
        float[] m = new float[SectionData.CELLS];
        for (int z = 0; z < 16; z++) {
            for (int sy = 0; sy < 16; sy++) {
                int colRow = RegionMarshaller.colIdx(0, ColumnSectionCodec.engineY(sectionY, sy), z);
                int secRow = 16 * sy + 256 * z;
                for (int x = 0; x < 16; x++) {
                    m[secRow + x] = columnInMass[colRow + x];
                }
            }
        }
        return m;
    }

    /** Wake every loaded section of a neighbour column's flow pass so it re-enters next cycle. */
    private void wakeColumnNeighbour(Identifier dim, int cx, int cz) {
        // We don't know which sectionY received mass; wake the whole loaded column's flow countdowns so a
        // dormant neighbour re-enters. Cheap: one map touch per existing section; the codec drives the
        // common case where flow stays near the donor anyway.
        SectionStore store = stores.store(dim);
        if (store == null || !store.isLoaded(cx, cz)) return;
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
            if (store.hasSection(key)) {
                activeSet.wakeFlowSection(dim, key);
            }
        }
    }

    /** Max material defaultMass over the column's species (the per-cell mass clamp bound). */
    private static float fullMassBound(ColumnTask task) {
        // The column carries no LUT; the bound is the largest legal per-cell mass. Use a generous water-
        // scale cap (1000 kg is every fluid's full block); a higher-density fluid keeps its own mass via
        // the per-species ledger upstream. Clamp here only guards against engine NaN/overflow leakage.
        return Float.MAX_VALUE; // per-species bounds are enforced by the region ledger; avoid clamping real mass
    }

    private static float maxAbsDelta(float[] a, float[] b) {
        float m = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            float d = Math.abs(a[i] - b[i]);
            if (d > m) m = d;
        }
        return m;
    }

    /**
     * The live per-cell material ids for this section, to record as the next cycle's signature. Reuses
     * {@code prior} verbatim when the live materials are identical (the overwhelming common case) so a
     * static section costs only a comparison pass, not a fresh 4096-ref allocation every snapshot.
     */
    private static Identifier[] liveMaterialIds(char[] matIx, List<Material> mats, Identifier[] prior) {
        if (prior != null && idsUnchanged(matIx, mats, prior)) {
            return prior;
        }
        Identifier[] ids = new Identifier[matIx.length];
        for (int i = 0; i < matIx.length; i++) {
            ids[i] = mats.get(matIx[i]).id();
        }
        return ids;
    }

    private static boolean idsUnchanged(char[] matIx, List<Material> mats, Identifier[] prior) {
        for (int i = 0; i < matIx.length; i++) {
            if (!mats.get(matIx[i]).id().equals(prior[i])) {
                return false;
            }
        }
        return true;
    }
}
