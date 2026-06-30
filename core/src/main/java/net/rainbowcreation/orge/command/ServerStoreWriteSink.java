package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.WakeSink;
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
    /** Nullable: when set, a write wakes the section's relevant pass (DESIGN §10 Decision 11 trigger
     *  (b)) so a {@code /orge set}/{@code fill} into a settled cell re-runs the simulation. */
    private final WakeSink wake;

    public ServerStoreWriteSink(SectionStoreManager stores) {
        this(stores, null);
    }

    public ServerStoreWriteSink(SectionStoreManager stores, WakeSink wake) {
        this.stores = stores;
        this.wake = wake;
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
        // Law §6/§7: T is never stored — encode the kelvin edit to stored extensive E through the named
        // temperature<->enthalpy seam (kelvin clamp + massless/unresolved 0-J fallback live there; E is
        // never clamped).
        float e = DerivedTemperature.encode(kelvin, data.massAt(cell), data.materialAt(cell));
        data.setEnthalpy(cell, e);
        store.put(key, data);
        if (wake != null) wake.wakeThermalSection(dimension, key); // a temp edit re-runs conduction
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
        if (wake != null) wake.wakeFlowSection(dimension, key); // a mass edit re-runs advection
    }
}
