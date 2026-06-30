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
 * extensive E is NEVER clamped. The read sink ({@link ServerStoreReadSource}) and the write sink
 * ({@link ServerStoreWriteSink}) both call through here so the encode/decode stay a single source of truth.
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
