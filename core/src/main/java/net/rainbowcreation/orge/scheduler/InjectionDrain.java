package net.rainbowcreation.orge.scheduler;

import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineInjection;
import net.rainbowcreation.orge.material.Material;

/**
 * Pure drain step (spec B3): for each placement intent in a column, override the cell back to its
 * incumbent (recorded engine-output species + stored mass) and emit an {@link EngineInjection} that
 * places the new species — resolved against the batch LUT — which the engine applies as a
 * displace-and-inject.
 *
 * <p><b>Ordering invariant (load-bearing):</b> this drain runs AFTER
 * {@link ColumnAssembler#assemble} / {@link MaterialChangeReseed#apply} in
 * {@code MinecraftThermalWorld.snapshotColumns}. For a water-over-air placement, the assembler will
 * have already seeded the new species (water, +1000 kg) into the assembled column arrays. This
 * override STOMPS that cell back to the recorded incumbent (air + stored mass) so the engine
 * injection is the SINGLE authoritative placement of the new species — seeded exactly once, by the
 * engine. Do NOT reorder this drain before the per-column assemble/reseed; the stomp depends on
 * their output being present.
 */
public final class InjectionDrain {

    /** Resolves the engine-cell's stored incumbent mass (kg). */
    @FunctionalInterface
    public interface IncumbentMass {
        float massAt(int engineCell);
    }

    private InjectionDrain() {
    }

    /**
     * @param columnId         this column's position in the batch (the injection's {@code columnId}).
     * @param matIx            the assembled column material indices (length CHUNK_N) — mutated in place.
     * @param mass             the assembled column masses (length CHUNK_N) — mutated in place.
     * @param lut              the batch LUT (species index space).
     * @param intents          this column's drained intents.
     * @param incumbentId      engine-cell → recorded incumbent species id (or {@code null} if unknown).
     * @param incumbentMass    engine-cell → stored incumbent mass.
     * @param out              accumulates the emitted injections.
     */
    public static void applyToColumn(int columnId, char[] matIx, float[] mass, List<Material> lut,
                                     List<PendingInjections.Intent> intents,
                                     IntFunction<Identifier> incumbentId, IncumbentMass incumbentMass,
                                     List<EngineInjection> out) {
        for (PendingInjections.Intent in : intents) {
            int cell = in.cell();
            if (cell < 0 || cell >= matIx.length) {
                continue;
            }
            char species = lutIndexOf(lut, in.species());
            if (species == 0) {
                continue;   // new species not in this batch LUT (shouldn't happen) — skip, keep live cell
            }
            Identifier incId = incumbentId.apply(cell);
            char incIx = incId != null ? lutIndexOf(lut, incId) : 0;
            // Stomp the cell back to its incumbent (overriding whatever assemble/reseed wrote above)
            // so the engine injection is the single authoritative placement of the new species.
            matIx[cell] = incIx;
            mass[cell] = incIx != 0 ? incumbentMass.massAt(cell) : 0f;
            out.add(new EngineInjection(columnId, cell, species, in.mass(), in.temperature()));
        }
    }

    private static char lutIndexOf(List<Material> lut, Identifier id) {
        for (int i = 0; i < lut.size(); i++) {
            if (lut.get(i).id().equals(id)) {
                return (char) i;
            }
        }
        return 0;   // void/unknown
    }
}
