package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Solar / radiational surface forcing (spec 2026-10-25 §3), the first {@link SkyColumnPass} visitor. Each
 * sky-exposed cell gains {@code q·dt} of enthalpy {@code E}, with {@code q = w·(S − σ·(T⁴ − T_sky⁴))}:
 * absorbed sunlight by day and long-wave loss to the sky at every hour. {@code T} is derived (law #6/#7)
 * and enters the emission term only; the loss is floored at {@code −C·(T − T_sky)} so one pass never
 * cools a cell below its sky (the sink's discrete maximum principle).
 *
 * <p>Every change is a boundary source/sink: the {@link #ledger} books the stored float difference per
 * dimension, so {@code Σ E + ledger} is exact. Server-thread, like the store it writes.</p>
 */
public final class SurfaceForcing {

    /** Stefan–Boltzmann constant [W/(m²·K⁴)]. */
    public static final double SIGMA = 5.670374419e-8;

    /** Global annual mean solar flux absorbed at the surface [W/m²] (Trenberth, Fasullo & Kiehl 2009). */
    public static final double SOLAR_ABSORBED_MEAN = 161.0;

    /** Overworld sky: the same budget's 333 W/m² back radiation as a black body, {@code (333/σ)^¼} [K]. */
    public static final double OVERWORLD_SKY_K = 276.8;

    /** The End's sky is deep space: the cosmic microwave background [K] (Fixsen 2009). */
    public static final double END_SKY_K = 2.725;

    /** Ticks in one Minecraft day; {@code dayTime} 0 is sunrise, 6000 noon, 12000 sunset. */
    public static final long DAY_TICKS = 24000L;

    public static final Identifier OVERWORLD = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    public static final Identifier THE_END = Identifier.fromNamespaceAndPath("minecraft", "the_end");

    /** The sky a dimension's surface sees this instant: absorbed sun {@code solar} [W/m²] and {@code skyK}. */
    public record Climate(double solar, double skyK) {

        /**
         * Overworld: {@code S = π·161·max(0, sin(2π·dayTime/24000))·(1 − rain)}, so a clear day's mean is
         * exactly 161 W/m² (noon peak 506); rain blocks the direct sun. The End: no sun, CMB sky. Any other
         * dimension (the Nether's bedrock ceiling): no forcing.
         */
        public static Optional<Climate> forDimension(Identifier dim, long dayTime, float rainLevel) {
            if (OVERWORLD.equals(dim)) {
                double phase = 2.0 * Math.PI * Math.floorMod(dayTime, DAY_TICKS) / DAY_TICKS;
                double clear = 1.0 - Math.min(1.0, Math.max(0.0, rainLevel));
                double sun = Math.PI * SOLAR_ABSORBED_MEAN * Math.max(0.0, Math.sin(phase)) * clear;
                return Optional.of(new Climate(sun, OVERWORLD_SKY_K));
            }
            if (THE_END.equals(dim)) {
                return Optional.of(new Climate(0.0, END_SKY_K));
            }
            return Optional.empty();
        }
    }

    /** Cumulative boundary energy [J] per dimension: + into the world, − out to the sky. */
    private final Map<Identifier, Double> ledger = new HashMap<>();

    /** The cumulative forcing energy booked for {@code dim} [J] (0 when never forced). */
    public double ledger(Identifier dim) {
        return ledger.getOrDefault(dim, 0.0);
    }

    /**
     * Force column {@code (cx, cz)} of {@code dim}'s {@code store} for {@code dtSeconds} under {@code climate}.
     * Returns the energy booked [J] (also added to {@link #ledger(Identifier)}).
     */
    public double applyColumn(Identifier dim, SectionStore store, int cx, int cz, Climate climate,
                              double dtSeconds, Function<Identifier, Material> lookup) {
        double[] booked = {0.0};
        Map<SubchunkKey, SectionData> touched = new HashMap<>();
        SkyColumnPass.walk(store, cx, cz, lookup, (key, data, cell, w) -> {
            // Double E (issue #44): a per-second sky flux far below a float ULP of a massive cell's E is
            // kept, not rounded away; booked is what the cell actually received.
            double before = data.enthalpyAt(cell);
            double after = before + deltaE(data, cell, w, climate, dtSeconds, lookup);
            data.setEnthalpy(cell, after);
            booked[0] += after - before;
            touched.put(key, data);
        });
        touched.forEach(store::put); // mark the forced sections' column dirty so the next save keeps the E
        ledger.merge(dim, booked[0], Double::sum);
        return booked[0];
    }

    /** {@code ΔE = max(q·dt, −C·max(0, T − T_sky))}, {@code q = w·(S − σ·(T⁴ − T_sky⁴))}. */
    static double deltaE(SectionData data, int cell, double w, Climate climate, double dt,
                         Function<Identifier, Material> lookup) {
        double capacity = 0.0;   // C = Σ m_k·cp_k [J/K]
        double h0 = 0.0;         // Σ m_k·h_k(0) [J]
        int occupied = 0;
        Material only = null;
        for (int k = 0; k < SectionData.SLOTS; k++) {
            Identifier id = k == 0 ? data.materialAt(cell) : data.slotMaterialAt(k, cell);
            float m = k == 0 ? data.massAt(cell) : data.slotMassAt(k, cell);
            Material mat = lookup.apply(id);
            if (mat == null || m <= 0f) {
                continue; // empty slot: no mass, no heat capacity
            }
            capacity += (double) m * mat.heatCapacity();
            h0 += (double) m * EnthalpyCurve.hOf(mat, lookup, 0f);
            occupied++;
            only = mat;
        }
        double sky = climate.skyK();
        double t = occupied == 1
                ? EnthalpyCurve.deriveT(data.enthalpyAt(cell), data.cellMassAt(cell), only, lookup, (float) sky)
                : (data.enthalpyAt(cell) - h0) / (capacity + 1e-12);
        double t4 = t * t * t * t;
        double sky4 = sky * sky * sky * sky;
        double q = w * (climate.solar() - SIGMA * (t4 - sky4));
        return Math.max(q * dt, -capacity * Math.max(0.0, t - sky));
    }
}
