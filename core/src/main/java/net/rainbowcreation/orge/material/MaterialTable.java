package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for the engine-resident material table
 * (spec 2026-06-03-engine-resident-material-table §4). Both the engine registration and the column
 * assembly build their id↔slot mapping HERE, so a {@code matIx} value means the same material on every
 * {@code orgeStepWorld} call.
 *
 * <ul>
 *   <li>Slot 0 = {@link #VACUUM} sentinel (unchanged invariant).</li>
 *   <li>Slots 1..N = {@link MaterialRegistry#all()} sorted by namespaced id string (deterministic,
 *       run-stable, reload-stable for an unchanged datapack).</li>
 * </ul>
 */
public final class MaterialTable {

    private MaterialTable() {}

    /**
     * The index-0 vacuum sentinel (moved here from {@code scheduler.MaterialLut} so both the engine
     * registration and assembly reference it without a scheduler dependency). Lightest movable fluid:
     * molar/min/max 0 with FINITE viscosity (displaceable, not frozen); conductivity 0 keeps it inert.
     */
    public static final Material VACUUM = Material.builder(
                    Identifier.fromNamespaceAndPath("orge", "vacuum"))
            .thermalConductivity(0f)
            .heatCapacity(1f)
            .molarMass(0f)
            .defaultMass(0f)
            .defaultTemperature(Float.NaN)
            .viscosity(0f)
            .minMass(0f).maxMass(0f)
            .build();

    /** The ordered table: slot 0 = VACUUM, slots 1..N = {@code registry.all()} sorted by id string. */
    public static List<Material> ordered(MaterialRegistry registry) {
        List<Material> out = new ArrayList<>();
        out.add(VACUUM);
        registry.all().stream()
                .sorted(Comparator.comparing((Material m) -> m.id().toString()))
                .forEach(out::add);
        return List.copyOf(out);
    }

    /** Reverse map material id → fixed slot, for {@code ordered}. Guards the 65535-slot char ceiling. */
    public static Map<Identifier, Character> slots(List<Material> ordered) {
        if (ordered.size() > Character.MAX_VALUE + 1) {
            throw new IllegalStateException("material table overflow: more than 65535 materials");
        }
        Map<Identifier, Character> m = new HashMap<>(ordered.size() * 2);
        for (int i = 0; i < ordered.size(); i++) {
            m.put(ordered.get(i).id(), (char) i);
        }
        return Map.copyOf(m);
    }
}
