package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Optional;
import java.util.function.Function;

/**
 * The permanent last link in the read chain: the server's authoritative {@link SectionStore}.
 * Returns {@code empty} only when the dimension has no store; otherwise returns the stored
 * section, or the synthesized ambient baseline for a never-simulated section (flagged via
 * {@link SectionStore#hasSection}). Server-thread only.
 */
public final class ServerStoreReadSource implements ThermalReadSource {

    private final SectionStoreManager stores;

    public ServerStoreReadSource(SectionStoreManager stores) {
        this.stores = stores;
    }

    @Override
    public Optional<SectionView> section(Identifier dimension, SubchunkKey key) {
        SectionStore store = stores.store(dimension);
        if (store == null) {
            return Optional.empty();
        }
        SectionData data = store.get(key);
        boolean ambient = !store.hasSection(key);
        return Optional.of(new View(data, ambient));
    }

    private record View(SectionData data, boolean ambient) implements SectionView {
        @Override public float tempAt(int cell) {
            // Derive T from stored extensive E at the read boundary (law §7 — T is never stored).
            // S7 finalizes this display derive (fallback / curve-lookup wiring).
            ActiveMaterials.State mats = ActiveMaterials.current();
            Function<Identifier, Material> lookup = id -> mats.registry().get(id).orElse(null);
            Material material = lookup.apply(data.materialAt(cell));
            if (material == null) {
                return SectionData.DEFAULT_AMBIENT_K; // no resolvable species ⇒ no enthalpy curve
            }
            return EnthalpyCurve.deriveT(data.enthalpyAt(cell), data.massAt(cell), material, lookup,
                    SectionData.DEFAULT_AMBIENT_K);
        }
        @Override public float massAt(int cell) { return data.massAt(cell); }
        @Override public SectionData.Form form() { return data.form(); }
        // ambient() provided by the record component
    }
}
