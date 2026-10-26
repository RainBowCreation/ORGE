package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.StepValidator;
import net.rainbowcreation.orge.section.SectionData;

import java.util.function.Function;

/**
 * The named temperature&lt;-&gt;enthalpy seam (Law §6/§7: temperature is NEVER stored). The store holds
 * extensive enthalpy E [J]; this module is the only place that turns a kelvin edit into E ({@link #encode})
 * and re-derives kelvin from stored E ({@link #decode}). Both directions resolve the cell's species against
 * the live {@link ActiveMaterials} table, walk its {@link EnthalpyCurve} (with latent plateaus), and apply
 * the {@link StepValidator#clampDerivedKelvin [0,6000]} clamp at the intensive boundary — the stored
 * extensive E is NEVER clamped. The {@link ServerCellStore} read view and write path both call through
 * here so the encode/decode stay a single source of truth.
 */
public final class DerivedTemperature {

    private DerivedTemperature() {}

    /**
     * DECODE (read boundary): derive the display temperature [K] from a cell's stored extensive enthalpy
     * {@code enthalpyJ} and {@code massKg} on {@code species}' enthalpy curve ({@code T = h⁻¹(E/m)}, latent
     * plateaus respected), then clamp to the {@code [0,6000]} derive boundary so a corrupt/extreme stored E
     * can't paint a nonsense T. A massless cell or an unresolved species has no enthalpy curve and falls
     * back to {@link SectionData#DEFAULT_AMBIENT_K}. The stored E is never touched.
     */
    public static float decode(double enthalpyJ, float massKg, Identifier species) {
        ActiveMaterials.State mats = ActiveMaterials.current();
        Function<Identifier, Material> lookup = id -> mats.registry().get(id).orElse(null);
        Material material = lookup.apply(species);
        if (material == null) {
            return SectionData.DEFAULT_AMBIENT_K; // no resolvable species ⇒ no enthalpy curve
        }
        float t = EnthalpyCurve.deriveT(enthalpyJ, massKg, material, lookup, SectionData.DEFAULT_AMBIENT_K);
        return StepValidator.clampDerivedKelvin(t);
    }

    /**
     * DECODE of a stored cell (read boundary), over its whole composition (law v5 #6/#7): {@code T} is
     * derived from the cell's ONE stored {@code E} on the MIXTURE curve {@code E = Σ_k m_k·h_k(T)}
     * ({@link EnthalpyCurve#mixtureT}), the same T the engine reads, then clamped to {@code [0,6000]}. A
     * cell with at most one occupied resolvable component is {@link #decode} on that component's curve
     * — bit-identical to the one-component readout.
     */
    public static float decodeCell(SectionData data, int cell) {
        ActiveMaterials.State mats = ActiveMaterials.current();
        Function<Identifier, Material> lookup = id -> mats.registry().get(id).orElse(null);
        Composition c = composition(data, cell, lookup);
        if (c.n <= 1) {
            return c.n == 1 ? decode(data.enthalpyAt(cell), c.masses[0], c.ids[0])
                    : decode(data.enthalpyAt(cell), data.massAt(cell), data.materialAt(cell));
        }
        float t = EnthalpyCurve.mixtureT(data.enthalpyAt(cell), c.materials, c.masses, c.n, lookup,
                SectionData.DEFAULT_AMBIENT_K);
        return StepValidator.clampDerivedKelvin(t);
    }

    /**
     * ENCODE of a stored cell (write boundary), over its whole composition: {@code E = Σ_k m_k·h_k(T)} at
     * the clamped {@code kelvin} — the inverse of {@link #decodeCell}. A cell with at most one occupied
     * resolvable component is {@link #encode} on that component's curve.
     */
    public static double encodeCell(float kelvin, SectionData data, int cell) {
        ActiveMaterials.State mats = ActiveMaterials.current();
        Function<Identifier, Material> lookup = id -> mats.registry().get(id).orElse(null);
        Composition c = composition(data, cell, lookup);
        if (c.n <= 1) {
            return c.n == 1 ? encode(kelvin, c.masses[0], c.ids[0])
                    : encode(kelvin, data.massAt(cell), data.materialAt(cell));
        }
        float k = StepValidator.clampDerivedKelvin(kelvin);
        double e = 0.0;
        for (int i = 0; i < c.n; i++) {
            e += EnthalpyCurve.cellE(c.masses[i], c.materials[i], lookup, k);
        }
        return e;
    }

    /** The cell's occupied ({@code m > 0}), resolvable composition slots, in slot order. */
    private record Composition(Identifier[] ids, Material[] materials, float[] masses, int n) {}

    private static Composition composition(SectionData data, int cell, Function<Identifier, Material> lookup) {
        Identifier[] ids = new Identifier[SectionData.SLOTS];
        Material[] ms = new Material[SectionData.SLOTS];
        float[] masses = new float[SectionData.SLOTS];
        int n = 0;
        for (int k = 0; k < SectionData.SLOTS; k++) {
            Identifier id = k == 0 ? data.materialAt(cell) : data.slotMaterialAt(k, cell);
            float m = k == 0 ? data.massAt(cell) : data.slotMassAt(k, cell);
            Material material = id == null ? null : lookup.apply(id);
            if (material == null || !(m > 0f)) {
                continue;
            }
            ids[n] = id; ms[n] = material; masses[n] = m; n++;
        }
        return new Composition(ids, ms, masses, n);
    }

    /**
     * ENCODE (write boundary): turn a {@code kelvin} edit into the stored extensive enthalpy {@code E = m·h(T)}
     * [J] for a cell of {@code massKg} on {@code species}' enthalpy curve. The intensive {@code kelvin} is
     * clamped to the {@code [0,6000]} derive boundary FIRST (a clamp on an intensive INPUT is sanctioned); the
     * resulting extensive E is NEVER clamped. An unresolved species or a massless cell carries no enthalpy and
     * encodes 0 J.
     */
    public static float encode(float kelvin, float massKg, Identifier species) {
        ActiveMaterials.State mats = ActiveMaterials.current();
        Function<Identifier, Material> lookup = id -> mats.registry().get(id).orElse(null);
        Material material = lookup.apply(species);
        float k = StepValidator.clampDerivedKelvin(kelvin);
        return (material == null || massKg <= 0f)
                ? 0f : (float) EnthalpyCurve.cellE(massKg, material, lookup, k);
    }
}
