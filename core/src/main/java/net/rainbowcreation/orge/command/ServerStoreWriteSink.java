package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * Server-authoritative writes into the live {@link SectionStore}. Each write reads the section
 * (materializing ambient if needed), sets the cell, and writes it back so the column is marked
 * dirty and the next scheduler snapshot picks the change up. Server-thread only.
 *
 * <p>Clobber safety: {@link SectionData} is mutable and {@link SectionStore#get} returns the
 * live stored instance after the first {@link SectionStore#put}. This means a subsequent
 * {@link #writeMass} on the same cell reads the already-modified instance (preserving the
 * previously written temperature) and writes it back — no data is lost.</p>
 */
public final class ServerStoreWriteSink implements ThermalWriteSink {

    private final SectionStoreManager stores;

    public ServerStoreWriteSink(SectionStoreManager stores) {
        this.stores = stores;
    }

    @Override
    public boolean isLoaded(Identifier dimension, SubchunkKey key) {
        SectionStore store = stores.store(dimension);
        return store != null && store.isLoaded(key.cx(), key.cz());
    }

    @Override
    public void writeTemp(Identifier dimension, SubchunkKey key, int cell, float kelvin) {
        SectionStore store = stores.store(dimension);
        if (store == null) {
            return;
        }
        SectionData data = store.get(key);
        data.setTemperature(cell, kelvin);
        store.put(key, data);
    }

    @Override
    public void writeMass(Identifier dimension, SubchunkKey key, int cell, float kg) {
        SectionStore store = stores.store(dimension);
        if (store == null) {
            return;
        }
        SectionData data = store.get(key);
        data.setMass(cell, kg);
        store.put(key, data);
    }
}
