package net.rainbowcreation.orge.scheduler;

import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineInjection;

/**
 * Pure drain step (spec B3): for each placement intent in a column, override the cell back to its
 * incumbent (recorded engine-output species + stored mass) and emit an {@link EngineInjection} that
 * places the new species — resolved (and REGISTERED) into the batch LUT via {@link SpeciesResolver} —
 * which the engine applies as a displace-and-inject.
 *
 * <p><b>Why a resolver, not a plain {@code List<Material>} lookup:</b> the placed species may be
 * ABSENT from the live snapshot — e.g. a stale step already stomped the just-placed fluid back to air,
 * or it is the only/first instance in the region — so it is not yet in the batch LUT. A non-appending
 * lookup returned 0 (void) for it, the injection was dropped, and the intent was cleared anyway: the
 * placement vanished. The resolver resolves the intent's species id to a {@link
 * net.rainbowcreation.orge.material.Material} and APPENDS it to the batch {@code MaterialLut}, so the
 * injection species index is always valid for a registered material.</p>
 *
 * <p><b>Durability:</b> only intents whose injection is actually emitted are added to
 * {@code emittedOut}; the scheduler clears exactly those after a successful write-back. An
 * unresolvable species (genuinely unregistered material) emits nothing, touches nothing, and stays
 * queued for a later retry rather than being silently lost.</p>
 *
 * <p><b>Ordering invariant (load-bearing):</b> this drain runs AFTER
 * {@link ColumnAssembler#assemble} in
 * {@code MinecraftThermalWorld.snapshotColumns}. For a water-over-air placement, the assembler will
 * have already seeded the new species (water, +1000 kg) into the assembled column arrays. This
 * override STOMPS that cell back to the recorded incumbent (air + stored mass) so the engine
 * injection is the SINGLE authoritative placement of the new species — seeded exactly once, by the
 * engine. Do NOT reorder this drain before the per-column assemble; the stomp depends on
 * its output being present.
 */
public final class InjectionDrain {

    /** Resolves the engine-cell's stored incumbent mass (kg). */
    @FunctionalInterface
    public interface IncumbentMass {
        float massAt(int engineCell);
    }

    /** Resolves a species id to its batch-LUT index, REGISTERING (appending) it if absent. Returns 0
     *  (void) only when the id maps to no known material. */
    @FunctionalInterface
    public interface SpeciesResolver {
        char indexOf(Identifier speciesId);
    }

    private InjectionDrain() {
    }

    /**
     * @param columnId         this column's position in the batch (the injection's {@code columnId}).
     * @param matIx            the assembled column material indices (length CHUNK_N) — mutated in place.
     * @param mass             the assembled column masses (length CHUNK_N) — mutated in place.
     * @param resolver         species id → batch-LUT index (appends the species to the LUT if absent).
     * @param intents          this column's queued intents.
     * @param incumbentId      engine-cell → recorded incumbent species id (or {@code null} if unknown).
     * @param incumbentMass    engine-cell → stored incumbent mass.
     * @param out              accumulates the emitted injections.
     * @param emittedOut       accumulates the intents that were actually emitted (clear-on-success set).
     */
    public static void applyToColumn(int columnId, char[] matIx, float[] mass, SpeciesResolver resolver,
                                     List<PendingInjections.Intent> intents,
                                     IntFunction<Identifier> incumbentId, IncumbentMass incumbentMass,
                                     List<EngineInjection> out, List<PendingInjections.Intent> emittedOut) {
        for (PendingInjections.Intent in : intents) {
            int cell = in.cell();
            if (cell < 0 || cell >= matIx.length) {
                continue;
            }
            char species = resolver.indexOf(in.species());
            if (species == 0) {
                continue;   // unregistered material — leave the cell + the intent alone (retry later)
            }
            Identifier incId = incumbentId.apply(cell);
            char incIx = incId != null ? resolver.indexOf(incId) : 0;
            // Stomp the cell back to its incumbent (overriding whatever assemble/reseed wrote above)
            // so the engine injection is the single authoritative placement of the new species.
            matIx[cell] = incIx;
            mass[cell] = incIx != 0 ? incumbentMass.massAt(cell) : 0f;
            out.add(new EngineInjection(columnId, cell, species, in.mass(), in.temperature()));
            emittedOut.add(in);
        }
    }
}
