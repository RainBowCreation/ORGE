package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless orchestration for the vertical cross-section fluid fall (DESIGN §10 Phase-2b /
 * cross-section fall, slice-1). The native engine steps each 16×16×16 section against a
 * READ-ONLY halo, so a section can never write its neighbour — fluid piles on each section's
 * floor (y=0) and never falls into the section below. This pass, run in the JAVA layer AFTER
 * the engine step, bridges the Y seam: it reads the donor floor + receiver top out of the §5
 * {@link SectionStore}, applies the pure per-column transfer rules of
 * {@link CrossSectionFluidLogic}, writes the result back into BOTH sections, updates the
 * {@link CellMaterialTracker} the same cycle (so the next snapshot's {@link MaterialChangeReseed}
 * does not mistake the drain/fill for a player edit and re-inflate "mass from nothing"), and
 * wakes the receiver's (and donor's) flow pass.
 *
 * <p>Depends only on headless-constructible types ({@link SectionStore}, {@link CellMaterialTracker},
 * the logic, the LUT) plus a {@link FlowWaker} callback, so it is unit-testable without a Minecraft
 * server — that is why this helper exists separately from {@link MinecraftThermalWorld}.</p>
 */
public final class CrossSectionSeamPass {

    private static final Logger LOGGER = LoggerFactory.getLogger("ORGE");

    private CrossSectionSeamPass() {}

    /** Sink for the per-section flow-wake trigger (delegates to the active set in the live impl). */
    @FunctionalInterface
    public interface FlowWaker {
        void wake(Identifier dim, SubchunkKey key);
    }

    /** Full cell index of plane column {@code c} (x-fastest) at section-local height {@code y}. */
    private static int cellIndex(int c, int y) {
        int x = c & 15;
        int z = (c >> 4) & 15;
        return x + 16 * y + 256 * z;
    }

    /**
     * Settle each entry's floor plane down into the section directly below it.
     *
     * @param store   live §5 section store (donor + receiver are read and written here)
     * @param tracker per-cell material tracker (read for prior species, updated post-transfer)
     * @param entries this step's batch entries, each treated as an UPPER (donor) section
     * @param lut     the batch material LUT (index 0 = VOID); maps species char ↔ {@link Material}
     * @param waker   flow-wake sink for the touched receiver/donor sections
     * @return the touched sections (donor + receiver) with their post-transfer species arrays,
     *         for downstream re-render marking; empty when nothing moved; at most one entry per
     *         distinct (dim,key) — the last write wins when the same section is touched multiple
     *         times in one run (e.g. a middle section that is both receiver and donor)
     */
    public static List<ThermalWorld.TouchedSection> run(
            SectionStore store, CellMaterialTracker tracker,
            List<ThermalWorld.BatchEntry> entries, List<Material> lut, FlowWaker waker) {

        // id -> index map (hoisted; index 0 = VOID). On duplicate ids the first wins.
        Map<Identifier, Character> idToIx = new HashMap<>();
        for (int i = 0; i < lut.size(); i++) {
            idToIx.putIfAbsent(lut.get(i).id(), (char) i);
        }

        // Accumulate the FINAL per-key species array (last write wins — each pair rebuilds from
        // the freshly-updated store/tracker, so the last pair to touch a section has the most
        // current state). Insertion-ordered so downstream order is deterministic.
        Map<DimKey, ThermalWorld.TouchedSection> touchedByKey = new LinkedHashMap<>();

        // Deduplicated wake targets (each section waked at most once per run).
        Set<DimKey> toWake = new LinkedHashSet<>();

        for (ThermalWorld.BatchEntry entry : entries) {
            Identifier dim = entry.dimension();
            SubchunkKey upper = entry.key();

            // Defensive: an entry whose own section isn't loaded shouldn't occur.
            if (!store.hasSection(upper)) continue;

            SubchunkKey lower = new SubchunkKey(upper.cx(), upper.sectionY() - 1, upper.cz());

            // Unloaded receiver: fluid rests on the donor floor; no-op (no wake, no transfer).
            if (!store.hasSection(lower)) continue;

            Identifier[] priorA = tracker.prior(dim, upper);
            // Donor untracked: nothing reliable to move.
            if (priorA == null) continue;

            Identifier[] priorB = tracker.prior(dim, lower);
            // Receiver never stepped: wake it so it becomes tracked next cycle, then skip THIS pair
            // (documented one-cycle lag).
            if (priorB == null) {
                toWake.add(new DimKey(dim, lower));
                continue;
            }

            // Map the full 4096 priors to species indices (unknown id -> 0/void).
            char[] spAFull = toSpecies(priorA, idToIx);
            char[] spBFull = toSpecies(priorB, idToIx);

            SectionData dataA = store.get(upper);
            SectionData dataB = store.get(lower);

            // Extract the 256 seam planes: donor = A y=0, receiver = B y=15.
            float[] massA = new float[NeighborHalo.FACE_CELLS];
            float[] tempA = new float[NeighborHalo.FACE_CELLS];
            char[] spA = new char[NeighborHalo.FACE_CELLS];
            float[] massB = new float[NeighborHalo.FACE_CELLS];
            float[] tempB = new float[NeighborHalo.FACE_CELLS];
            char[] spB = new char[NeighborHalo.FACE_CELLS];
            for (int c = 0; c < NeighborHalo.FACE_CELLS; c++) {
                int ia = cellIndex(c, 0);
                int ib = cellIndex(c, 15);
                massA[c] = dataA.massAt(ia);
                tempA[c] = dataA.temperatureAt(ia);
                spA[c] = spAFull[ia];
                massB[c] = dataB.massAt(ib);
                tempB[c] = dataB.temperatureAt(ib);
                spB[c] = spBFull[ib];
            }

            CrossSectionFluidLogic.SeamResult result =
                    CrossSectionFluidLogic.settleVerticalSeam(massA, tempA, spA, massB, tempB, spB, lut);

            // Conservation regression tripwire — log but never throw.
            double drift = Math.abs(result.massAfter() - result.massBefore());
            if (drift > 1e-3) {
                LOGGER.warn("[ORGE] cross-section seam mass drift {} for {}", drift, entry.key());
            }

            if (!anyChanged(result.changedA()) && !anyChanged(result.changedB())) {
                continue;  // no store/tracker writes, no wake, no TouchedSection
            }

            // Write the mutated planes back. Writing only changed columns keeps a UNIFORM section
            // from needless promotion when nothing in its plane moved.
            for (int c = 0; c < NeighborHalo.FACE_CELLS; c++) {
                if (result.changedA()[c]) {
                    int ia = cellIndex(c, 0);
                    dataA.setMass(ia, massA[c]);
                    dataA.setTemperature(ia, tempA[c]);
                    spAFull[ia] = spA[c];
                }
                if (result.changedB()[c]) {
                    int ib = cellIndex(c, 15);
                    dataB.setMass(ib, massB[c]);
                    dataB.setTemperature(ib, tempB[c]);
                    spBFull[ib] = spB[c];
                }
            }

            store.put(upper, dataA);
            store.put(lower, dataB);

            // Record the post-transfer species so the next reseed treats this drain/fill as known.
            // Round-trip char -> Identifier: a non-void index uses lut.get(idx).id(); a void cell (idx 0)
            // reuses the original prior id so void/untracked cells keep their identity, never becoming the
            // orge:void sentinel.
            tracker.record(dim, upper, toIds(spAFull, lut, priorA));
            tracker.record(dim, lower, toIds(spBFull, lut, priorB));

            // Queue wakes — deduplicated via Set; actual callbacks dispatched after all pairs.
            toWake.add(new DimKey(dim, lower));   // receiver
            toWake.add(new DimKey(dim, upper));   // donor

            // Accumulate touched sections. Last write wins when the same section appears in
            // multiple pairs (e.g. a middle section that is both a receiver and a donor): each
            // pair rebuilds spXFull from the freshly-updated tracker/store, so the last array
            // passed here is already the most current. We re-read from the updated tracker so
            // the array stored in the map is the FINAL post-all-pairs state.
            touchedByKey.put(new DimKey(dim, upper), new ThermalWorld.TouchedSection(dim, upper, spAFull));
            touchedByKey.put(new DimKey(dim, lower), new ThermalWorld.TouchedSection(dim, lower, spBFull));
        }

        // Dispatch deduplicated wakes.
        for (DimKey dk : toWake) {
            waker.wake(dk.dim(), dk.key());
        }

        return new ArrayList<>(touchedByKey.values());
    }

    /** Lightweight (dim, key) pair used as a map/set key within a single run. */
    private record DimKey(Identifier dim, SubchunkKey key) {}

    private static boolean anyChanged(boolean[] flags) {
        for (boolean b : flags) {
            if (b) return true;
        }
        return false;
    }

    private static char[] toSpecies(Identifier[] ids, Map<Identifier, Character> idToIx) {
        char[] sp = new char[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Character ix = ids[i] == null ? null : idToIx.get(ids[i]);
            sp[i] = ix == null ? 0 : ix;
        }
        return sp;
    }

    /** char species -> Identifier; index 0 (void) reuses the original {@code prior} id at that cell. */
    private static Identifier[] toIds(char[] sp, List<Material> lut, Identifier[] prior) {
        Identifier[] ids = new Identifier[sp.length];
        for (int i = 0; i < sp.length; i++) {
            ids[i] = sp[i] == 0 ? prior[i] : lut.get(sp[i]).id();
        }
        return ids;
    }
}
