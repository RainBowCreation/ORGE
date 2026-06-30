package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.WakeSink;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Optional;

/**
 * The server-authoritative {@link CellStore}: the permanent read source AND write sink over the live
 * §5 {@link SectionStore}. This is the deep module that absorbs the old {@code ServerStoreReadSource}
 * and {@code ServerStoreWriteSink} slivers — every reach into {@link SectionData} internals
 * ({@code materialAt}/{@code setMaterialAt}, {@code massAt}/{@code setMass}, {@code setEnthalpy},
 * {@code markExternalEdit}) and every collaborator (the {@link DerivedTemperature} encode/decode seam,
 * the {@link CellSpeciesSource} species resolver, the {@link WakeSink}) lives behind its three methods.
 * Server-thread only.
 *
 * <h2>Read</h2>
 * Returns {@code empty} only when the dimension has no store; otherwise the stored section, or the
 * synthesized ambient baseline for a never-simulated section (flagged via {@link SectionStore#hasSection}).
 * Temperature is NEVER stored (Law §6/§7): the view re-derives it each call from the cell's stored
 * extensive enthalpy E and mass through {@link DerivedTemperature#decode}.
 *
 * <h2>Write</h2>
 * {@link #write} sets the cell in one shot. The ordering and side effects it hides — each one a shipped
 * bugfix the seam must keep:
 * <ul>
 *   <li><b>mass before temperature</b>: the new mass is applied first so the enthalpy encode
 *       {@code E = mass·h(T)} uses the final mass (else a read derives {@code T·oldMass/newMass});</li>
 *   <li><b>species before encode</b>: a write onto a not-yet-simulated cell (durable material absent ⇒
 *       the {@code orge:vacuum} sentinel) first adopts its LIVE world species via the injected
 *       {@link CellSpeciesSource} — the same block first-touch identity the snapshot would resolve — so
 *       the encode runs on the right enthalpy curve instead of storing 0 J and losing the edit. A cell
 *       that already carries a real durable species is never clobbered;</li>
 *   <li><b>edit-epoch guard</b>: the section is marked externally edited ({@link SectionData#markExternalEdit})
 *       so the scheduler's stale in-flight {@code ColumnWriteBack} skips it instead of overwriting the
 *       direct edit;</li>
 *   <li><b>wake</b>: a mass change re-runs advection (flow) and the temperature change re-runs conduction
 *       (thermal), so an edit into a settled cell re-enters the simulation.</li>
 * </ul>
 *
 * <p>Clobber safety: {@link SectionData} is mutable and {@link SectionStore#get} returns the live stored
 * instance after the first {@link SectionStore#put}, so the read-modify-write within a single
 * {@link #write} never loses data.</p>
 */
public final class ServerCellStore implements CellStore {

    private final SectionStoreManager stores;
    /** Nullable: when set, a write wakes the section's relevant pass (DESIGN §10 Decision 11 trigger
     *  (b)) so a {@code /orge set}/{@code fill} into a settled cell re-runs the simulation. */
    private final WakeSink wake;
    /** Nullable: resolves the live block species for a write onto an unestablished (vacuum) cell. */
    private final CellSpeciesSource species;

    public ServerCellStore(SectionStoreManager stores) {
        this(stores, null, null);
    }

    public ServerCellStore(SectionStoreManager stores, WakeSink wake) {
        this(stores, wake, null);
    }

    public ServerCellStore(SectionStoreManager stores, WakeSink wake, CellSpeciesSource species) {
        this.stores = stores;
        this.wake = wake;
        this.species = species;
    }

    @Override
    public Optional<SectionView> read(Identifier dimension, SubchunkKey key) {
        SectionStore store = stores.store(dimension);
        if (store == null) {
            return Optional.empty();
        }
        SectionData data = store.get(key);
        boolean ambient = !store.hasSection(key);
        return Optional.of(new View(data, ambient));
    }

    @Override
    public boolean isLoaded(Identifier dimension, SubchunkKey key) {
        SectionStore store = stores.store(dimension);
        return store != null && store.isLoaded(key.cx(), key.cz());
    }

    @Override
    public void write(Identifier dimension, SubchunkKey key, int cell, float temperatureK, Float massKg) {
        SectionStore store = stores.store(dimension);
        if (store == null) {
            return;
        }
        SectionData data = store.get(key);
        // Establish the cell's live species FIRST: a temp edit on a never-simulated cell would otherwise
        // encode against the orge:vacuum stub (0 J) and be lost. Adopting the world block's species lands
        // the encode on the right enthalpy curve (and the next snapshot reads stored E, not a re-seed).
        establishSpecies(data, dimension, key, cell);
        // Mass BEFORE temperature: the encode below reads the cell's CURRENT mass, so the new mass must
        // land first — otherwise a read derives T·oldMass/newMass (set 300 K @ 3000 kg over a 1.2 kg cell
        // read back 0.12 K). A null mass means "leave mass unchanged" (the temp re-encodes at it).
        boolean massWritten = massKg != null;
        if (massWritten) {
            data.setMass(cell, massKg);
        }
        // Law §6/§7: T is never stored — encode the kelvin edit to stored extensive E through the named
        // temperature<->enthalpy seam (kelvin clamp + massless/unresolved 0-J fallback live there; E is
        // never clamped).
        float e = DerivedTemperature.encode(temperatureK, data.massAt(cell), data.materialAt(cell));
        data.setEnthalpy(cell, e);
        data.markExternalEdit(); // guard the edit from a stale in-flight write-back clobbering it
        store.put(key, data);
        if (wake != null) {
            if (massWritten) wake.wakeFlowSection(dimension, key); // a mass edit re-runs advection
            wake.wakeThermalSection(dimension, key);               // a temp edit re-runs conduction
        }
    }

    /**
     * Give a not-yet-established cell (durable material absent ⇒ {@code orge:vacuum} sentinel) its
     * LIVE world species, so the temperature encode lands on the correct enthalpy curve. A cell that
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

    private record View(SectionData data, boolean ambient) implements SectionView {
        @Override public float tempAt(int cell) {
            // Law §6/§7: T is NEVER stored — re-derive it each call from stored extensive E through the
            // named temperature<->enthalpy seam (clamp + massless/unresolved fallback live there).
            return DerivedTemperature.decode(data.enthalpyAt(cell), data.massAt(cell), data.materialAt(cell));
        }
        @Override public float massAt(int cell) { return data.massAt(cell); }
        @Override public Identifier material(int cell) { return data.materialAt(cell); }
        @Override public SectionData.Form form() { return data.form(); }
        // ambient() provided by the record component
    }
}
