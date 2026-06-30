package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.WakeSink;
import net.rainbowcreation.orge.section.MaterialPalette;
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
 *
 * <p>Identity establishment: a write that lands on a not-yet-simulated cell hits a synthesized
 * ambient section whose durable material is the {@code orge:vacuum} sentinel. Encoding a temperature
 * (E = m·h(T)) against vacuum yields 0 J and the edit is lost (and a later snapshot re-seeds the cell
 * to its block's {@code default_temperature}). So before encoding, an unestablished cell adopts its
 * LIVE world species via the injected {@link CellSpeciesSource} — the same block first-touch identity
 * the snapshot would resolve — so the encode runs on the right enthalpy curve and persists. A cell
 * that already carries a durable species keeps it (no clobber of simulated identity).</p>
 */
public final class ServerStoreWriteSink implements ThermalWriteSink {

    private final SectionStoreManager stores;
    /** Nullable: when set, a write wakes the section's relevant pass (DESIGN §10 Decision 11 trigger
     *  (b)) so a {@code /orge set}/{@code fill} into a settled cell re-runs the simulation. */
    private final WakeSink wake;
    /** Nullable: resolves the live block species for a write onto an unestablished (vacuum) cell. */
    private final CellSpeciesSource species;

    public ServerStoreWriteSink(SectionStoreManager stores) {
        this(stores, null, null);
    }

    public ServerStoreWriteSink(SectionStoreManager stores, WakeSink wake) {
        this(stores, wake, null);
    }

    public ServerStoreWriteSink(SectionStoreManager stores, WakeSink wake, CellSpeciesSource species) {
        this.stores = stores;
        this.wake = wake;
        this.species = species;
    }

    /**
     * Give a not-yet-established cell (durable material absent ⇒ {@code orge:vacuum} sentinel) its
     * LIVE world species, so a temperature encode lands on the correct enthalpy curve. A cell that
     * already carries a real durable species is left untouched. No-op when no resolver is wired or it
     * cannot resolve (no server / chunk unloaded).
     */
    private void establishSpecies(SectionData data, Identifier dimension, SubchunkKey key, int cell) {
        if (species == null) {
            return;
        }
        Identifier current = data.materialAt(cell);
        if (current != null && !MaterialPalette.VACUUM_ID.equals(current)) {
            return; // already established (simulated / placed identity) — never clobber it
        }
        Identifier live = species.speciesAt(dimension, key, cell);
        if (live != null && !MaterialPalette.VACUUM_ID.equals(live)) {
            data.setMaterialAt(cell, live);
        }
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
        // Establish the cell's live species FIRST: a temp edit on a never-simulated cell would otherwise
        // encode against the orge:vacuum stub (0 J) and be lost. Adopting the world block's species lands
        // the encode on the right enthalpy curve (and the next snapshot reads stored E, not a re-seed).
        establishSpecies(data, dimension, key, cell);
        // Law §6/§7: T is never stored — encode the kelvin edit to stored extensive E through the named
        // temperature<->enthalpy seam (kelvin clamp + massless/unresolved 0-J fallback live there; E is
        // never clamped).
        float e = DerivedTemperature.encode(kelvin, data.massAt(cell), data.materialAt(cell));
        data.setEnthalpy(cell, e);
        data.markExternalEdit(); // guard the edit from a stale in-flight write-back clobbering it
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
        // Establish identity on the same terms as writeTemp so a mass edit on a fresh cell also leaves a
        // resolvable species behind (keeps the stored cell consistent if a snapshot interleaves).
        establishSpecies(data, dimension, key, cell);
        data.setMass(cell, kg);
        data.markExternalEdit(); // guard the edit from a stale in-flight write-back clobbering it
        store.put(key, data);
        if (wake != null) wake.wakeFlowSection(dimension, key); // a mass edit re-runs advection
    }
}
