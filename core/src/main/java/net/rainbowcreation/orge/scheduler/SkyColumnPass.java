package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The once-per-second sky heightmap pass (spec 2026-10-25 §2): walks each of a column's 256 {@code (x, z)}
 * cell columns top-down through the stored {@link SectionStore} sections and hands every sky-exposed cell
 * to a {@link Visitor} with its exposure weight. Reusable: the surface forcing ({@link SurfaceForcing}) is
 * the first visitor, rain seeding ({@link RainSeeding}) the second, evaporation ({@link Evaporation}) the
 * third; they share one walk through
 * {@link Visitor#andThen} instead of scanning again.
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

        /** The walk of one {@code (x, z)} cell column is done (after its last exposed cell, if any). */
        default void columnDone() { }

        /** This visitor, then {@code next}, on each exposed cell — so several consumers share ONE walk. */
        default Visitor andThen(Visitor next) {
            Visitor first = this;
            return new Visitor() {
                @Override
                public void exposed(SubchunkKey key, SectionData data, int cell, double w) {
                    first.exposed(key, data, cell, w);
                    next.exposed(key, data, cell, w);
                }

                @Override
                public void columnDone() {
                    first.columnDone();
                    next.columnDone();
                }
            };
        }
    }

    /** One {@code (x, z)} cell column's surface: its cell of highest exposure, handed over at {@code columnDone}. */
    @FunctionalInterface
    public interface SurfaceVisitor {
        /** {@code data} holds the surface at section cell {@code cell}; {@code sumW} is the column's {@code Σw}. */
        void surface(SubchunkKey key, SectionData data, int cell, double sumW);
    }

    /**
     * A {@link Visitor} that finds each {@code (x, z)} column's surface — the cell of highest exposure {@code w}
     * (the top-most on a tie) — and hands it with the column's {@code Σw} to {@code s} once the column is done.
     * A column with no exposed cell has no surface. Air ({@code ε = 0}) is never it, the first condensed cell is,
     * and a one-block roof is (rain #33 §2; evaporation #34 §1). Compares exposures with each other only.
     */
    public static Visitor surface(SurfaceVisitor s) {
        return new Visitor() {
            private SubchunkKey bestKey;
            private SectionData best;
            private int bestCell;
            private double bestW;
            private double sumW;

            @Override
            public void exposed(SubchunkKey key, SectionData data, int cell, double w) {
                sumW += w;
                if (w > bestW) {   // the first (top-most) of equal exposures keeps it
                    bestKey = key;
                    best = data;
                    bestCell = cell;
                    bestW = w;
                }
            }

            @Override
            public void columnDone() {
                if (best != null) {
                    s.surface(bestKey, best, bestCell, sumW);
                }
                bestKey = null;
                best = null;
                bestW = 0.0;
                sumW = 0.0;
            }
        };
    }

    private SkyColumnPass() { }

    /**
     * {@link #walk} with visitors that write the cells they are handed: every section the walk handed out
     * is re-{@code put} afterwards, marking its column dirty so the next save keeps the change.
     */
    public static void walkAndPut(SectionStore store, int cx, int cz,
                                  Function<Identifier, Material> lookup, Visitor visitor) {
        Map<SubchunkKey, SectionData> touched = new HashMap<>();
        walk(store, cx, cz, lookup, visitor.andThen((key, data, cell, w) -> touched.put(key, data)));
        touched.forEach(store::put);
    }

    /** Walk column {@code (cx, cz)} of {@code store}, resolving stored material ids through {@code lookup}. */
    public static void walk(SectionStore store, int cx, int cz,
                            Function<Identifier, Material> lookup, Visitor visitor) {
        // One registry lookup per distinct id per column, not per cell slot (issue #51: the pass runs on the
        // server thread over every stored cell above the surface).
        Map<Identifier, Optional<Material>> resolved = new HashMap<>();
        Function<Identifier, Material> memo =
                id -> resolved.computeIfAbsent(id, k -> Optional.ofNullable(lookup.apply(k))).orElse(null);
        // Each stored section once per column: its palette resolved to materials, and whether none of them
        // emits (ε = 0 for every cell, e.g. dry air) — such a section hands out no exposure and passes τ
        // through unchanged, so the cell walk skips it whole.
        int n = ColumnAssembler.MAX_SECTION_Y - ColumnAssembler.MIN_SECTION_Y + 1;
        Section[] sections = new Section[n];
        for (int sectionY = ColumnAssembler.MIN_SECTION_Y; sectionY <= ColumnAssembler.MAX_SECTION_Y; sectionY++) {
            SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
            if (store.hasSection(key)) {
                Section sec = new Section(key, store.get(key), memo);
                sections[sectionY - ColumnAssembler.MIN_SECTION_Y] = sec.transparent ? null : sec;
            }
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                walkCellColumn(x, z, visitor, sections);
                visitor.columnDone();
            }
        }
    }

    /** One stored section of the walked column, its palette resolved once to materials. */
    private static final class Section {
        final SubchunkKey key;
        final SectionData data;
        /** Slot-0 material per palette slot (the unset marker reads as vacuum), composition-slot material per
         *  palette slot, and the material a cell with no layer reads ({@code orge:vacuum}). */
        final Material[] slot0;
        final Material[] slotK;
        final Material vacuum;
        final boolean transparent;
        private final Function<Identifier, Material> lookup;

        Section(SubchunkKey key, SectionData data, Function<Identifier, Material> lookup) {
            this.key = key;
            this.data = data;
            this.lookup = lookup;
            List<Identifier> raw = data.rawPalette();
            slot0 = new Material[raw.size()];
            slotK = new Material[raw.size()];
            vacuum = lookup.apply(MaterialPalette.VACUUM_ID);
            for (int j = 0; j < raw.size(); j++) {
                Identifier id = raw.get(j);
                slotK[j] = lookup.apply(id);
                slot0[j] = MaterialPalette.UNSET_ID.equals(id) ? vacuum : slotK[j];
            }
            transparent = transparent(data, lookup);
        }

        /** {@link SkyColumnPass#cellEmissivity} over the resolved palette. */
        double emissivity(int cell) {
            double vSum = 0.0;
            double vEps = 0.0;
            int slots = data.hasSlots() ? SectionData.SLOTS : 1;   // no composition layer: slots 1.. are empty
            for (int k = 0; k < slots; k++) {
                int j = k == 0 ? data.materialSlotAt(cell) : data.slotMaterialSlotAt(k, cell);
                // A visitor that wrote a new species into a cell it was handed grew the palette; only that
                // (already walked) cell can reference the new slots, but resolve such an index directly.
                Material mat = j < 0 ? vacuum
                        : j >= slotK.length ? lookup.apply(k == 0 ? data.materialAt(cell) : data.slotMaterialAt(k, cell))
                        : k == 0 ? slot0[j] : slotK[j];
                if (mat == null) {
                    continue; // unknown / vacuum id: no substance, no volume
                }
                float m = k == 0 ? data.massAt(cell) : data.slotMassAt(k, cell);
                double v = Math.max(0f, m) / ((double) mat.defaultMass() + EPS_V);
                vSum += v;
                vEps += v * mat.emissivity();
            }
            return vEps / (vSum + EPS_V);
        }
    }

    /** Whether no cell of {@code data} can emit: every material of its palette — which slot 0 and the
     *  composition slots both intern into — has ε = 0, so every cell's volume-mixed ε is 0. */
    private static boolean transparent(SectionData data, Function<Identifier, Material> lookup) {
        for (Identifier id : data.palette()) {
            Material m = lookup.apply(id);
            if (m != null && m.emissivity() != 0.0) {
                return false;
            }
        }
        // A section with no material layer reads every cell as orge:vacuum, which is in the palette.
        return true;
    }

    private static void walkCellColumn(int x, int z, Visitor visitor, Section[] sections) {
        double tau = 1.0;
        for (int sectionY = ColumnAssembler.MAX_SECTION_Y; sectionY >= ColumnAssembler.MIN_SECTION_Y; sectionY--) {
            Section sec = sections[sectionY - ColumnAssembler.MIN_SECTION_Y];
            if (sec == null) {
                continue; // not stored (no state), or transparent
            }
            for (int sy = 15; sy >= 0; sy--) {
                int cell = x + 16 * sy + 256 * z;
                double eps = sec.emissivity(cell);
                double w = tau * eps;
                if (w > 0.0) {
                    visitor.exposed(sec.key, sec.data, cell, w);
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
        int slots = data.hasSlots() ? SectionData.SLOTS : 1;   // no composition layer: slots 1.. are empty
        for (int k = 0; k < slots; k++) {
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
