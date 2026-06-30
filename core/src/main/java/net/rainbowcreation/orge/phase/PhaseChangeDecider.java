package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The whole-section §7 phase decision, decoupled from the live world. Given one section's stored
 * data, the engine's post-swap species ({@code matOut}), and the active material registry, it
 * computes exactly what the {@code MinecraftPhaseChanger} must do: which cells get a new
 * {@code representative_block} drawn, and which surviving pinned sources get re-pinned to a new
 * stored enthalpy. The adapter is left with nothing but the block-write and the enthalpy-write.
 *
 * <p>This is the load-bearing decision the §7 changer used to interleave with ~5 separate registry
 * lookups inside a 170-line server method. Here the registry is injected ONCE as a single
 * {@code id → Optional<Material>} accessor (the live caller passes {@code registry()::get}), the
 * stored-species temperature derivation and the Dirichlet re-pin enthalpy are computed in this pure
 * unit, and the remaining MC-typed pieces (per-cell live block, block placement, the
 * {@code SectionData} mutation) stay in the adapter. Pure (no Minecraft world access) → it is the
 * test surface, no {@code ServerLevel} mock required.</p>
 *
 * <p>Behaviour mirrors the old adapter exactly: stored-species enthalpy → temperature ({@link
 * EnthalpyCurve#deriveT}, law §7 — T is never stored; a massless/unresolvable cell reads ambient),
 * {@link PhasePlanner} over the engine-out species ({@link EngineOutSpecies}, live-block fallback)
 * with a material-exists gate, {@link SourcePinPlanner} for the conditional re-pin, and {@link
 * PhaseRenderResolver} for the material → block draw.</p>
 */
public final class PhaseChangeDecider {

    /** Draw block {@code block} at section-local cell {@code cellIndex} (the §7 target's repr block). */
    public record Placement(int cellIndex, Identifier block) {}

    /** Re-pin: store extensive enthalpy {@code enthalpyJ} [J] at cell {@code cellIndex} (Dirichlet hold). */
    public record EnthalpyWrite(int cellIndex, float enthalpyJ) {}

    /** The section's complete phase decision: blocks to place, then enthalpies to re-pin. */
    public record Plan(List<Placement> placements, List<EnthalpyWrite> enthalpyWrites) {}

    private PhaseChangeDecider() {}

    /**
     * @param data             the section's stored mass / enthalpy / material-id per cell (read-only here;
     *                         the returned {@link EnthalpyWrite}s are applied by the caller)
     * @param outMaterial      the engine's per-cell output species ({@code matOut}), or null on a
     *                         non-advection / back-compat path
     * @param outLut           the step's batch material table resolving those indices, or null
     * @param liveBlockMaterial the live world block's material per cell — the fallback species when the
     *                          engine reported none (the adapter reads the section block here)
     * @param registry         the active material registry as {@code id → Optional<Material>}; the single
     *                          source for the species curve, the material-exists gate, and the repr block
     * @param ambientK         the temperature a massless/unresolvable cell derives to
     */
    public static Plan plan(SectionData data,
                            char[] outMaterial,
                            List<Material> outLut,
                            IntFunction<Material> liveBlockMaterial,
                            Function<Identifier, Optional<Material>> registry,
                            float ambientK) {
        final Function<Identifier, Material> lookup = id -> registry.apply(id).orElse(null);

        // Read mass + derive T from the stored extensive E (law §7 — T is never stored) using the
        // cell's STORED species curve. Mass gates the phase rule so drained/empty cells never
        // transition (Bug B); a massless / unresolvable cell derives to ambient (no curve).
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            mass[i] = data.massAt(i);
            Material cellM = lookup.apply(data.materialAt(i));
            temps[i] = cellM == null ? ambientK
                    : EnthalpyCurve.deriveT(data.enthalpyAt(i), mass[i], cellM, lookup, ambientK);
        }

        // Pair each cell with the species the engine says it BECAME this step (matOut), not the live
        // block: the native molar-sort swaps fluids vertically (lava sinks under water) and the
        // reconciler rewrites blocks only AFTER this changer runs, so the live block is still the
        // OUTGOING material. EngineOutSpecies falls back to the live block when the engine reported no
        // species (null args / vacuum sentinel) so a surviving pinned source still reads as its source.
        IntFunction<Material> cellMat = i ->
                EngineOutSpecies.resolve(outMaterial, outLut, i, liveBlockMaterial.apply(i));

        // The planner works in MATERIAL ids; its existence check is "does this target MATERIAL exist?"
        // — the block to draw is the separate material → representative_block lookup below.
        List<PhasePlanner.Transition> transitions =
                PhasePlanner.plan(temps, mass, cellMat, id -> registry.apply(id).isPresent());
        List<SourcePinPlanner.Reset> resets = SourcePinPlanner.plan(cellMat, transitions);

        // Resolve each target MATERIAL → its representative_block (identity is the material id; the
        // block is only what is drawn). Drop a transition whose material/repr is unavailable.
        List<Placement> placements = new ArrayList<>(transitions.size());
        for (PhasePlanner.Transition t : transitions) {
            Optional<Identifier> repr = PhaseRenderResolver.representativeBlock(registry, t.materialId());
            if (repr.isEmpty()) {
                continue; // material unregistered → nothing to draw
            }
            placements.add(new Placement(t.cellIndex(), repr.get()));
        }

        // Conditional re-pin (engine-audit C): hold surviving source cells at their
        // default_temperature, stored as extensive E (law §7) = mass·h(T) via the cell's STORED
        // species curve; an unresolvable / massless cell stores 0 J.
        List<EnthalpyWrite> writes = new ArrayList<>(resets.size());
        for (SourcePinPlanner.Reset r : resets) {
            int ci = r.cellIndex();
            Material cellM = lookup.apply(data.materialAt(ci));
            float massKg = data.massAt(ci);
            float e = (cellM == null || massKg <= 0f)
                    ? 0f : (float) EnthalpyCurve.cellE(massKg, cellM, lookup, r.temperatureK());
            writes.add(new EnthalpyWrite(ci, e));
        }

        return new Plan(placements, writes);
    }
}
