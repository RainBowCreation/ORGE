package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.function.Function;

/** A stored cell's derived {@code T} (law #6/#7: {@code E} is the truth, {@code T} is derived from it). */
final class CellTemperature {

    private CellTemperature() { }

    /**
     * Its material's curve for a one-component cell, the linear mixture {@code (E − Σ m_j·h_j(0)) / Σ m_j·cp_j}
     * otherwise (the {@link SurfaceForcing} rule).
     */
    static double derived(SectionData data, int cell, Function<Identifier, Material> lookup) {
        double capacity = 0.0;
        double h0 = 0.0;
        int occupied = 0;
        Material only = null;
        for (int s = 0; s < SectionData.SLOTS; s++) {
            Material m = lookup.apply(s == 0 ? data.materialAt(cell) : data.slotMaterialAt(s, cell));
            float mass = s == 0 ? data.massAt(cell) : data.slotMassAt(s, cell);
            if (m == null || mass <= 0f) {
                continue;
            }
            capacity += (double) mass * m.heatCapacity();
            h0 += (double) mass * EnthalpyCurve.hOf(m, lookup, 0f);
            occupied++;
            only = m;
        }
        return occupied == 1
                ? EnthalpyCurve.deriveT(data.enthalpyAt(cell), data.cellMassAt(cell), only, lookup,
                        SectionData.DEFAULT_AMBIENT_K)
                : (data.enthalpyAt(cell) - h0) / (capacity + 1e-12);
    }
}
