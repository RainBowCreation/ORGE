package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Grand mass, grand enthalpy {@code E} and per-species mass summed over sections — the books a
 * save-format migration must leave unchanged (PRD #24 constraint 4, law v5 #9 per component).
 *
 * <p>Every sum runs cell by cell, slot 0 then slots 1..K−1, in double. Two views of the same
 * cells therefore give bit-identical totals, so equality here is exact, not a tolerance. A cell
 * with no material layer books its mass under {@link MaterialPalette#VACUUM_ID}.</p>
 */
public final class ColumnTotals {

    private double massKg;
    private double energyJ;
    private final TreeMap<Identifier, Double> speciesKg = new TreeMap<>();

    public double massKg() {
        return massKg;
    }

    public double energyJ() {
        return energyJ;
    }

    /** Mass [kg] per species id; read-only. */
    public Map<Identifier, Double> speciesKg() {
        return Collections.unmodifiableMap(speciesKg);
    }

    private void cell(Identifier id, double m) {
        massKg += m;
        speciesKg.merge(id, m, Double::sum);
    }

    /** Adds a decoded section, every slot of every cell. */
    public void add(SectionData s) {
        for (int i = 0; i < SectionData.CELLS; i++) {
            cell(s.materialAt(i), s.massAt(i));
            for (int k = 1; k < SectionData.SLOTS; k++) {
                cell(s.slotMaterialAt(k, i), s.slotMassAt(k, i));
            }
            energyJ += s.enthalpyAt(i);
        }
    }

    /** Adds a section exactly as its wire payload states it (before {@link SectionData} adopts it). */
    void add(SectionSnapshot w) {
        for (int i = 0; i < SectionData.CELLS; i++) {
            Identifier id = w.hasMaterials() ? w.materialPalette().get(w.materialIndices()[i]) : MaterialPalette.VACUUM_ID;
            cell(id, w.isFull() ? w.mass()[i] : w.uniformMass());
            for (int k = 1; k < SectionData.SLOTS; k++) {
                int q = (k - 1) * SectionData.CELLS + i;
                cell(w.hasSlots() ? w.materialPalette().get(w.slotIndices()[q]) : MaterialPalette.VACUUM_ID,
                        w.hasSlots() ? w.slotMass()[q] : 0f);
            }
            energyJ += w.isFull() ? w.enthalpy()[i] : w.uniformEnthalpy();
        }
    }

    /** Adds every section of a column. */
    public void add(Map<Integer, SectionData> column) {
        for (SectionData s : column.values()) add(s);
    }

    /** Adds another set of totals. */
    public void add(ColumnTotals o) {
        massKg += o.massKg;
        energyJ += o.energyJ;
        o.speciesKg.forEach((id, m) -> speciesKg.merge(id, m, Double::sum));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ColumnTotals t
                && Double.compare(massKg, t.massKg) == 0
                && Double.compare(energyJ, t.energyJ) == 0
                && speciesKg.equals(t.speciesKg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(massKg, energyJ, speciesKg);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("mass %.6f kg, E %.6f J, species {", massKg, energyJ));
        String sep = "";
        for (Map.Entry<Identifier, Double> e : speciesKg.entrySet()) {
            if (e.getValue() == 0.0) continue;   // empty slots book 0 kg under orge:vacuum
            sb.append(sep).append(e.getKey()).append(String.format("=%.6f", e.getValue()));
            sep = ", ";
        }
        return sb.append('}').toString();
    }
}
