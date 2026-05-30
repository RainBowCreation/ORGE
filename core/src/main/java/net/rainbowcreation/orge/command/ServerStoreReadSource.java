package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Optional;

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
        @Override public float tempAt(int cell) { return data.temperatureAt(cell); }
        @Override public float massAt(int cell) { return data.massAt(cell); }
        @Override public SectionData.Form form() { return data.form(); }
        // ambient() provided by the record component
    }
}
