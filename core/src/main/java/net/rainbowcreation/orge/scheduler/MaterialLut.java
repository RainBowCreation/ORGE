package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialTable;

import java.util.List;
import java.util.Map;

/**
 * A read-only VIEW over a published {@link net.rainbowcreation.orge.material.ActiveMaterials.State}'s
 * stable material table (spec 2026-06-03-engine-resident-material-table §4/§6). {@code matIx} ids are
 * now globally STABLE: slot 0 = {@link #VACUUM}, slots 1..N fixed at load/{@code /reload} by
 * {@link MaterialTable}. {@code indexOf} returns the fixed slot for an id and NEVER appends.
 *
 * <p>(Historical: this class used to be {@code new}-ed per step and assigned indices in first-seen
 * encounter order. That batch-local model is gone — see the spec.)</p>
 */
public final class MaterialLut {

    /** The index-0 vacuum sentinel; aliases {@link MaterialTable#VACUUM} (its canonical home). */
    public static final Material VACUUM = MaterialTable.VACUUM;

    private final List<Material> ordered;          // slot -> material (slot 0 = VACUUM); immutable
    private final Map<Identifier, Character> byId;  // material id -> fixed slot; immutable

    /** Wrap a published stable table. Both args come from {@link MaterialTable} (same State). */
    public MaterialLut(List<Material> ordered, Map<Identifier, Character> byId) {
        this.ordered = ordered;
        this.byId = byId;
    }

    /** Fixed slot for {@code material}, or 0 (VACUUM) if its id is not in the table. Never appends. */
    public char indexOf(Material material) {
        return indexOf(material.id());
    }

    /** Fixed slot for {@code id}, or 0 (the {@link #VACUUM} sentinel) if this id is not in the table. */
    public char indexOf(Identifier id) {
        Character existing = byId.get(id);
        return existing != null ? existing : (char) 0;
    }

    /** The table handed to the engine register/assembly; index 0 = {@link #VACUUM}. Immutable. */
    public List<Material> materials() {
        return ordered;
    }
}
