package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.function.Function;

/**
 * The store half of {@link ColumnSnapshot}'s per-section reader: given a section's live-block geometry
 * (first-touch matIx + block-derived mass) and the §5 {@link SectionStore}, produce the
 * {@link ColumnAssembler.SectionCells} the assembler consumes. Pure — the live world is reached only
 * through the injected first-touch materials, so the headless suite drives the exact snapshot read.
 */
final class SectionCellsReader {

    private SectionCellsReader() {}

    static ColumnAssembler.SectionCells read(SectionStore store, SubchunkKey key,
                                             GeometryAssembler.Geometry geo,
                                             GeometryAssembler.CellMaterials cellMat,
                                             Function<Identifier, Material> lookup,
                                             char[] priorSpecies, float[] waterlogSeed, float ambientK) {
        float[] mass = sectionMass(store, key, geo);
        // Never simulated (no stored section): its waterlogged blocks have not carried their water yet.
        boolean seedWaterlog = store == null || !store.hasSection(key);
        // Durable identity (durable-material §): when this section has a stored material layer, the
        // stored id is AUTHORITATIVE per cell (a broken cell stays orge:vacuum). The assembler resolves
        // it into the batch LUT by appending. A cell with no durable id (no layer, or one its first
        // writer did not touch — issue #54) keeps storedMaterial null ⇒ the assembler falls back to the
        // block's first-touch geo.matIx().
        Identifier[] storedMaterial = new Identifier[SectionData.CELLS];
        if (store != null && store.hasSection(key) && store.get(key).hasMaterials()) {
            SectionData sd = store.get(key);
            for (int i = 0; i < SectionData.CELLS; i++) {
                storedMaterial[i] = sd.durableMaterialAt(i);
            }
        }
        // Extensive momentum + dynamic pressure + STORED absolute E: read per-cell stored values from
        // the SectionStore when available; otherwise zero-fill (never-simulated / back-compat default).
        // Threading p back in is what lets depth-pressure ACCUMULATE across engine steps (the JNI
        // rebuilds a fresh World each call, so without persisting p it would reset to 0 every step).
        // F2 (law §7 / §1.1): SectionCells.momX/Y/Z carry the raw STORED EXTENSIVE momentum p [kg·m/s],
        // sourced loss-free (NO v=p/m down-conversion at this seam) exactly like the stored E channel.
        // S6 (law §6/§7): SectionCells.enthalpy carries the raw STORED extensive E [J] (loss-free to
        // the engine's eIn); the engine's TEMPERATURE channel (tIn diagnostic) carries a DERIVED,
        // CLAMPED T = h⁻¹(E/m) per cell — NEVER the raw E. Never-simulated cells: E = 0 (void/empty).
        float[] momX = new float[SectionData.CELLS];
        float[] momY = new float[SectionData.CELLS];
        float[] momZ = new float[SectionData.CELLS];
        float[] p    = new float[SectionData.CELLS];
        float[] swapReady = new float[SectionData.CELLS];
        double[] enthalpy = new double[SectionData.CELLS];
        // DESIGN-LAW v5 #7 composition slots 1..K−1 (stored id + mass); empty for one-component cells.
        Identifier[] slotMaterial = new Identifier[SectionData.SLOT_CELLS];
        float[] slotMass = new float[SectionData.SLOT_CELLS];
        // The engine tIn channel: for a stored section it is DERIVED from E below; for a never-
        // simulated section it is the per-cell ambient/source seed.
        float[] temps;
        if (store != null && store.hasSection(key)) {
            SectionData sd = store.get(key);
            // A legacy (pre-composition) section seeds until its write-back clears the mark (issue #60).
            seedWaterlog = sd.isLegacyOneComponent();
            // Stamp the edit-epoch: the write-back of THIS cycle will skip the section if an
            // external edit (/orge set) lands after this read, so it can't clobber the edit.
            sd.markSnapshot();
            sd.copySlotsInto(slotMaterial, slotMass);
            temps = new float[SectionData.CELLS];
            for (int i = 0; i < SectionData.CELLS; i++) {
                float mi = mass[i];
                // F2 (law §7 / §1.1): EXTENSIVE momentum p [kg·m/s] crosses RAW (mirror of the stored E
                // channel) — NO v=p/m down-conversion here, and NO mass guard. A resting/thinned-then-
                // refilled cell carries its stored p regardless of current mass; a resting massless cell
                // already has stored p == 0, so the unguarded copy is exact in both cases.
                momX[i] = sd.momXAt(i);
                momY[i] = sd.momYAt(i);
                momZ[i] = sd.momZAt(i);
                p[i]    = sd.pAt(i);
                swapReady[i] = sd.swapReadyAt(i);
                // Raw stored absolute E [J] handed to the engine loss-free (NEVER as Kelvin).
                enthalpy[i] = sd.enthalpyAt(i);
                // Derive the engine's diagnostic tIn = h⁻¹(E/m), clamped to the [0,6000] derive
                // boundary. Per-cell species = stored material when this section carries a durable
                // layer, else the cell's first-touch block material. A massless / unresolvable cell
                // derives to ambient (no curve). This is the law-§6/§7 fix: the engine never receives
                // raw E in its Kelvin slot.
                Identifier durable = sd.durableMaterialAt(i);
                Material cellM = durable != null ? lookup.apply(durable) : cellMat.at(i);
                // An unset E (issue #54: a materialized ambient cell) is not a temperature to derive:
                // seed it exactly as a never-simulated cell (source default T, else biome ambient), so
                // the assembler encodes E = m·h(T_seed) from the seeded mass.
                float t = (sd.enthalpyAt(i) == SectionData.UNSET_ENTHALPY_J)
                        ? AmbientSeeder.seedT(cellM, ambientK)
                        : (cellM == null) ? ambientK
                        : EnthalpyCurve.deriveT(sd.enthalpyAt(i), mi, cellM, lookup, ambientK);
                temps[i] = StepValidator.clampDerivedKelvin(t);
            }
        } else {
            temps = AmbientSeeder.seed(cellMat::at, ambientK);
        }
        return new ColumnAssembler.SectionCells(geo.matIx(), mass, temps, priorSpecies, storedMaterial,
                momX, momY, momZ, p, swapReady, enthalpy, slotMaterial, slotMass, waterlogSeed, seedWaterlog);
    }

    /**
     * Input mass for one section: the stored/advected mass when the section has been simulated,
     * otherwise the block-derived geometry seed (a never-tracked section's true initial state — air
     * 1.2 kg, solids/fluids at their {@link Material#defaultMass()}). The fresh-fluid seed (a fluid
     * cell the store reports empty ⇒ {@code defaultMass}) is NOT applied here: it is owned by the
     * single seed in {@link ColumnAssembler} (DESIGN 2026-06-01 §6 — exactly one fresh-fluid seed in
     * the pipeline). This method only selects stored-vs-geometry, never fabricates fluid mass.
     */
    private static float[] sectionMass(SectionStore store, SubchunkKey key, GeometryAssembler.Geometry geo) {
        boolean has = store != null && store.hasSection(key);
        // Never-simulated section: block-derived initial state (includes air/solid masses ColumnAssembler
        // cannot reconstruct). Simulated section: the advected/stored mass verbatim — the fresh-fluid
        // seed for a stored-empty fluid cell is applied downstream by ColumnAssembler, not here.
        return has ? store.get(key).massArray().clone() : geo.mass();
    }
}
