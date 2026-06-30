package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Optional;

/**
 * The single read/write seam between the {@code /orge} command layer and the §5 thermal store
 * (DESIGN observability track, Topic A). A deep module: it presents a SMALL interface — read a
 * section, ask if a column is loaded, write one cell — while hiding the whole store-internal
 * machinery behind it (Law §6/§7 temperature&lt;-&gt;enthalpy encode/decode, never-simulated-cell
 * species establishment, the edit-epoch dirty mark, and the post-write simulation wake).
 *
 * <p>{@link OrgeCommandLogic} depends on exactly one {@code CellStore} and never touches
 * {@code SectionData} (or {@link DerivedTemperature}, or {@link CellSpeciesSource}) directly. The
 * server-authoritative implementation is {@link ServerCellStore}; {@link ReadChainCellStore} layers
 * a "prefer the fresher source, fall back to the server" read chain over it. Server-thread only.</p>
 */
public interface CellStore {

    /**
     * The section addressed by {@code (dimension, key)} as an immutable {@link SectionView} — the
     * stored section, or the synthesized never-simulated ambient baseline. {@code empty} means "no
     * store serves this dimension" (the caller reports it); a loaded-but-never-simulated section
     * still returns a (ambient-flagged) view.
     */
    Optional<SectionView> read(Identifier dimension, SubchunkKey key);

    /** Whether the column owning {@code key} is loaded — the precondition for a durable {@link #write}. */
    boolean isLoaded(Identifier dimension, SubchunkKey key);

    /**
     * Set one cell to {@code temperatureK}, and to {@code massKg} when it is non-null (a null mass
     * leaves the cell's mass unchanged). The store applies the mass BEFORE encoding the temperature so
     * the enthalpy {@code E = mass·h(T)} lands on the final mass, establishes a never-simulated cell's
     * live species before that encode, marks the section externally edited (so a stale in-flight
     * write-back cannot clobber the edit), and wakes the relevant simulation pass(es). All of that is
     * internal: the caller supplies only the cell, the temperature, and the optional mass.
     */
    void write(Identifier dimension, SubchunkKey key, int cell, float temperatureK, Float massKg);
}
