package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.function.Function;

/**
 * The once-per-second sky heightmap pass (spec 2026-10-25 §2): walks each of a column's 256 {@code (x, z)}
 * cell columns top-down through the stored {@link SectionStore} sections and hands every sky-exposed cell
 * to a {@link Visitor} with its exposure weight. Reusable: the surface forcing ({@link SurfaceForcing}) is
 * the first visitor; rain seeding and evaporation read the same walk instead of scanning again.
 *
 * <p>Exposure is Kirchhoff's grey body, with no classification of any cell: a cell takes {@code τ·ε_cell}
 * of what reaches it and passes {@code τ·(1 − ε_cell)} down, where {@code ε_cell} is the v5 §4.8 volume
 * mix of its slots' emissivities. Air ({@code ε = 0}) passes the sky through; the first condensed cell
 * takes almost all of it. The walk stops once {@code τ < }{@link #TAU_MIN} (the remaining flux is absorbed
 * nowhere). A section that is not stored holds no state and is skipped. Server-thread, like the store.</p>
 */
public final class SkyColumnPass {

    /** Transmission below which the walk stops: ≤ 1 ppm of the sky flux is left unabsorbed. */
    public static final double TAU_MIN = 1e-6;

    /** Regularizer for the volume shares: an empty cell ({@code Σv = 0}) mixes to {@code ε = 0}. */
    private static final double EPS_V = 1e-12;

    /** One sky-exposed cell: {@code data} holds it at section cell index {@code cell}. */
    @FunctionalInterface
    public interface Visitor {
        void exposed(SubchunkKey key, SectionData data, int cell, double exposure);
    }

    private SkyColumnPass() { }

    /** Walk column {@code (cx, cz)} of {@code store}, resolving stored material ids through {@code lookup}. */
    public static void walk(SectionStore store, int cx, int cz,
                            Function<Identifier, Material> lookup, Visitor visitor) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                walkCellColumn(store, cx, cz, x, z, lookup, visitor);
            }
        }
    }

    private static void walkCellColumn(SectionStore store, int cx, int cz, int x, int z,
                                       Function<Identifier, Material> lookup, Visitor visitor) {
        double tau = 1.0;
        for (int sectionY = ColumnAssembler.MAX_SECTION_Y; sectionY >= ColumnAssembler.MIN_SECTION_Y; sectionY--) {
            SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
            if (!store.hasSection(key)) {
                continue;
            }
            SectionData data = store.get(key);
            for (int sy = 15; sy >= 0; sy--) {
                int cell = x + 16 * sy + 256 * z;
                double eps = cellEmissivity(data, cell, lookup);
                double w = tau * eps;
                if (w > 0.0) {
                    visitor.exposed(key, data, cell, w);
                }
                tau -= w;
                if (tau < TAU_MIN) {
                    return;
                }
            }
        }
    }

    /**
     * {@code ε_cell = Σ_k θ_k·ε_k} with the volume shares {@code θ_k = v_k / Σ v}, {@code v_k = m_k/ρ0_k}
     * (v5 proposal §4.8). One occupied slot gives {@code θ = 1}, so {@code ε_cell = ε} at any fill.
     */
    static double cellEmissivity(SectionData data, int cell, Function<Identifier, Material> lookup) {
        double vSum = 0.0;
        double vEps = 0.0;
        for (int k = 0; k < SectionData.SLOTS; k++) {
            Identifier id = k == 0 ? data.materialAt(cell) : data.slotMaterialAt(k, cell);
            float m = k == 0 ? data.massAt(cell) : data.slotMassAt(k, cell);
            Material mat = lookup.apply(id);
            if (mat == null) {
                continue; // unknown / vacuum id: no substance, no volume
            }
            double v = Math.max(0f, m) / ((double) mat.defaultMass() + EPS_V);
            vSum += v;
            vEps += v * mat.emissivity();
        }
        return vEps / (vSum + EPS_V);
    }
}
