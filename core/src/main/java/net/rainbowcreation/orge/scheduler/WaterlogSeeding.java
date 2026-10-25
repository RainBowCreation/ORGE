package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The boundary ledger of the waterlogged-block water seed (proposal §12 Q4 (B), issue #60). On a section's
 * first simulation — never simulated, loaded from the pre-composition format, or a new placement — the
 * {@link ColumnAssembler} gives each {@code waterlogged} block {@code ρ_water·V_pore} of water at the cell's
 * temperature. That water and its enthalpy enter the world from outside, so they are booked here like rain
 * ({@link RainSeeding}): per dimension, the water [kg] and the energy [J] it brought.
 *
 * <p>The assembler only records what it seeded ({@link Seeds}, per section of one column); the write-back
 * books it when it persists that section. A held, failed or edit-skipped step lands nothing and books
 * nothing, and the next snapshot seeds again — so the books close against the store exactly, and the water
 * is booked once. Server-thread, like the store it describes.</p>
 */
public final class WaterlogSeeding {

    /** The water a waterlogged block holds (the only seeded species). */
    public static final Identifier WATER = ColumnAssembler.WATER_ID;

    /** Cumulative waterlogged-block water [kg] seeded per dimension. */
    private final Map<Identifier, Double> mass = new HashMap<>();
    /** Cumulative enthalpy [J] that water brought per dimension. */
    private final Map<Identifier, Double> energy = new HashMap<>();

    /** Waterlogged-block water [kg] seeded into {@code dim} so far. */
    public double mass(Identifier dim) {
        return mass.getOrDefault(dim, 0.0);
    }

    /** Enthalpy [J] the waterlogged-block water brought into {@code dim} so far. */
    public double energy(Identifier dim) {
        return energy.getOrDefault(dim, 0.0);
    }

    /** Book section {@code sectionY} of {@code seeds} — now persisted — into {@code dim}'s ledgers. */
    void book(Identifier dim, Seeds seeds, int sectionY) {
        double kg = seeds.kg(sectionY);
        if (kg > 0.0) {
            mass.merge(dim, kg, Double::sum);
            energy.merge(dim, seeds.joules(sectionY), Double::sum);
        }
    }

    /** The water one column's assembly seeded, per section: kg, and the stored {@code E} change [J]. */
    public static final class Seeds {
        private static final int SECTIONS = ColumnAssembler.MAX_SECTION_Y - ColumnAssembler.MIN_SECTION_Y + 1;
        private final double[] kg = new double[SECTIONS];
        private final double[] joules = new double[SECTIONS];

        void add(int sectionY, float waterKg, double dE) {
            kg[sectionY - ColumnAssembler.MIN_SECTION_Y] += waterKg;
            joules[sectionY - ColumnAssembler.MIN_SECTION_Y] += dE;
        }

        /** Water [kg] seeded into section {@code sectionY}. */
        public double kg(int sectionY) {
            return kg[sectionY - ColumnAssembler.MIN_SECTION_Y];
        }

        /** Enthalpy [J] seeded into section {@code sectionY}. */
        public double joules(int sectionY) {
            return joules[sectionY - ColumnAssembler.MIN_SECTION_Y];
        }
    }
}
