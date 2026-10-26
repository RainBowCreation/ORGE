package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionData;

import java.util.List;

/** Immutable read view of one section's per-cell thermal state, for observability. */
public interface SectionView {

    /** One occupied composition slot of a cell (law v5 #7): its material and mass [kg]. */
    record Component(Identifier material, float massKg) {}

    /**
     * Temperature (K) of cell {@code cell} (0..4095), DERIVED — never stored (Law §6/§7). Each call
     * re-derives T from the cell's stored extensive enthalpy E over its whole composition — the mixture
     * curve {@code E = Σ_k m_k·h_k(T)} (see {@link DerivedTemperature#decodeCell}); the result is clamped
     * to the {@code [0,6000]} derive boundary. A massless cell or an unresolved species has no enthalpy
     * curve and falls back to {@link SectionData#DEFAULT_AMBIENT_K}.
     */
    float tempAt(int cell);

    /** Total mass {@code M = Σ_k m_k} (kg) of cell {@code cell} (0..4095), over every composition slot. */
    float massAt(int cell);

    /**
     * The occupied composition slots of cell {@code cell} (law v5 #7), in slot order; a one-component
     * cell is its single {@code (material, massAt)} pair.
     */
    default List<Component> composition(int cell) {
        return List.of(new Component(material(cell), massAt(cell)));
    }

    /**
     * The STORED per-cell material id of cell {@code cell} (0..4095) — the sim's authoritative species
     * (e.g. {@code orge:vacuum} for a broken/drained cell), NOT the live Minecraft block's first-touch
     * mapping. Observability reads this so {@code /orge get} reflects sim truth after a break.
     */
    Identifier material(int cell);

    /** Storage form (UNIFORM/FULL) of the underlying section. */
    SectionData.Form form();

    /** True when this is the synthesized never-simulated ambient baseline (not stored/evolved). */
    boolean ambient();
}
