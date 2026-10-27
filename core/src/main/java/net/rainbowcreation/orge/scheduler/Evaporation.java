package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Daytime evaporation (spec 2026-10-25-daytime-evaporation, issues #34 and #80), the third
 * {@link SkyColumnPass} visitor: it rides the forcing's walk, so it costs no extra scan.
 *
 * <p><b>One owner per face (issue #80).</b> The engine's vapor exchange ({@code engine_b.hpp}, on by default
 * since #41) evaporates every liquid|gas face inside the simulated domain, day and night, at the Penman rate.
 * This pass therefore owns only the face the engine has no cell for: a surface whose top face is the top of
 * the world, where the vapor leaves the domain. By day and while it is not raining, such a column's surface
 * cell — the one rain lands on — gives water ({@code water.maxTarget} vapor) to the free atmosphere above.
 * Every other surface is left to the engine, so no face evaporates twice.</p>
 *
 * <p>The rate is the engine's exchange across that one face with the free atmosphere as receiver
 * ({@code w_gas = 1}, {@code p_v = 0}): {@code F = χ_pair·g_vap·A·dt·w_liq·p_sat(T_donor)} — the biome enters
 * through the water's own derived {@code T}. Clamped by the donor's cohesive floor (law #9). The vapor carries
 * {@code h_steam(T_donor)}: the donor pays the latent heat.</p>
 *
 * <p>The water's float mass changes by {@code x} on its own lattice (dithered, like #28's
 * {@code vap_quantize}), so H₂O is exact to the bit. The vapor is a boundary sink booked in {@link #mass} /
 * {@link #energy}, so {@code Σ E + ledgers} is exact. Server-thread, like the store it writes.</p>
 */
public final class Evaporation {

    /** Penman's still-air wind function f(u = 0) = 2.6 mm·day⁻¹·kPa⁻¹ [kg/(m²·s·Pa)] — the engine's #28 value. */
    public static final double G_VAP_PENMAN = 3.0e-8;

    /** The water that evaporates; its {@code maxTarget} is the vapor. */
    public static final Identifier WATER = RainSeeding.WATER;

    static final double P_ATM = 101325.0;   // Pa — the pressure the LUT's phase temperatures are measured at
    static final double R_GAS = 8.314;      // J/(mol·K), the engine's
    /** V_film [m³]: the liquid weight of a 1 mm film (engine {@code seepk::V_FILM}). */
    static final double V_FILM = 1e-3;
    private static final double V = 1.0;    // cell volume [m³]
    private static final double A = 1.0;    // top face [m²]

    /** Vapor that left the domain [kg] per dimension (≤ 0). */
    private final Map<Identifier, Double> mass = new HashMap<>();
    /** Enthalpy that left with it [J] per dimension: the donor's actual change of E, rounding included. */
    private final Map<Identifier, Double> energy = new HashMap<>();
    /** All water evaporated off world-top surfaces [kg] per dimension (diagnostic). */
    private final Map<Identifier, Double> evaporated = new HashMap<>();
    /** Passes run: the dither's salt. */
    private long passes;

    /** Vapor mass [kg] that left {@code dim}'s simulated domain so far (≤ 0). */
    public double mass(Identifier dim) {
        return mass.getOrDefault(dim, 0.0);
    }

    /** Boundary energy [J] booked for {@code dim}: the leaving vapor's enthalpy and the transfer's rounding. */
    public double energy(Identifier dim) {
        return energy.getOrDefault(dim, 0.0);
    }

    /** Water [kg] evaporated off {@code dim}'s world-top surfaces so far. */
    public double evaporated(Identifier dim) {
        return evaporated.getOrDefault(dim, 0.0);
    }

    /**
     * The evaporation of {@code climate} over {@code dtSeconds} as a {@link SkyColumnPass.Visitor} for {@code dim}.
     * Nothing evaporates unless the sun is up and no rain falls (the climate, not a material, decides). It
     * writes only the donor, a cell of the walk, which the caller re-{@code put}s ({@link SkyColumnPass#walkAndPut}).
     */
    public SkyColumnPass.Visitor visitor(Identifier dim, SurfaceForcing.Climate climate, double dtSeconds,
                                         Function<Identifier, Material> lookup) {
        return visitor(dim, climate, dtSeconds, lookup, nextDither(dim, climate, lookup));
    }

    /**
     * This pass's dither for {@code dim}, advancing the pass count — once per dimension per sky pass, and
     * only when the pass can evaporate at all — or {@code NaN} when it cannot. Taken on the thread that owns
     * this ledger, for a {@link #visitor(Identifier, SurfaceForcing.Climate, double, Function, double)} that
     * may run elsewhere (issue #51).
     */
    public double nextDither(Identifier dim, SurfaceForcing.Climate climate, Function<Identifier, Material> lookup) {
        Material water = lookup.apply(WATER);
        Material vapor = water == null || water.maxTarget() == null ? null : lookup.apply(water.maxTarget());
        if (water == null || vapor == null || !(climate.solar() > 0.0) || climate.rainKgPerM2s() > 0.0) {
            return Double.NaN;
        }
        return dither(++passes, dim);
    }

    /** The evaporation pass with the dither {@code u} of {@link #nextDither}: a no-op when it is {@code NaN}. */
    public SkyColumnPass.Visitor visitor(Identifier dim, SurfaceForcing.Climate climate, double dtSeconds,
                                         Function<Identifier, Material> lookup, double u) {
        Material water = lookup.apply(WATER);
        Material vapor = water == null || water.maxTarget() == null ? null : lookup.apply(water.maxTarget());
        if (Double.isNaN(u) || water == null || vapor == null) {
            return (key, data, cell, w) -> { };
        }
        double k = Math.max(chi(water), chi(vapor)) * G_VAP_PENMAN * A * dtSeconds;
        return SkyColumnPass.surface((key, data, cell, sumW) ->
                evaporate(dim, key, data, cell, water, vapor, k, u, lookup));
    }

    /** Add {@code pass}'s ledgers (one sky pass run elsewhere, issue #51) into this one's. */
    public void absorb(Evaporation pass) {
        pass.mass.forEach((dim, v) -> mass.merge(dim, v, Double::sum));
        pass.energy.forEach((dim, v) -> energy.merge(dim, v, Double::sum));
        pass.evaporated.forEach((dim, v) -> evaporated.merge(dim, v, Double::sum));
    }

    private void evaporate(Identifier dim, SubchunkKey key, SectionData data, int cell,
                           Material water, Material vapor, double k, double u,
                           Function<Identifier, Material> lookup) {
        if (key.sectionY() < ColumnAssembler.MAX_SECTION_Y || ((cell >> 4) & 15) < 15) {
            return; // a face inside the domain: the engine's vapor exchange owns it (issue #80)
        }
        int kc = slotOf(data, cell, water.id());
        if (kc < 0) {
            return; // no water on this surface
        }
        float mc = slotMass(data, kc, cell);
        double vc = (1.0 - chi(water)) * mc / particleDensity(water);
        double ta = CellTemperature.derived(data, cell, lookup);
        double ac = clamp01(1.0 - (condensedVolume(data, cell, lookup) - vc) / V);
        double budget = Math.max(0.0, mc - (1.0 - chi(water)) * ac * water.minMass());
        // The free atmosphere receives: w_gas = 1, p_v = 0, no saturation cap.
        double f = Math.min(budget, k * clamp01(vc / V_FILM) * pSat(water, vapor, ta));
        // The water's mass lattice: x a multiple of ulp(m_c), so m_c − x is exact (a lower binade is finer);
        // the sub-lattice remainder is dithered (unbiased over passes).
        double q = Math.ulp(mc);
        double x = Math.min(q * Math.floor(f / q + u), q * Math.floor(budget / q));
        if (!(x > 0.0)) {
            return;
        }
        float c1 = (float) (mc - x);

        double hv = EnthalpyCurve.hOf(vapor, lookup, (float) ta);
        float massA = data.cellMassAt(cell);
        setSlot(data, kc, cell, c1 > 0f ? water.id() : MaterialPalette.VACUUM_ID, c1);
        double ea0 = data.enthalpyAt(cell);
        data.setEnthalpy(cell, ea0 - x * hv);
        // The vapor leaves at the donor's velocity: the donor keeps v, its momentum scales with its mass.
        float keep = data.cellMassAt(cell) / massA;
        data.setMomentum(cell, data.momXAt(cell) * keep, data.momYAt(cell) * keep, data.momZAt(cell) * keep);
        evaporated.merge(dim, x, Double::sum);
        mass.merge(dim, -x, Double::sum);
        energy.merge(dim, data.enthalpyAt(cell) - ea0, Double::sum);
    }

    /**
     * The saturation pressure [Pa] of the phase pair (cold {@code c}, hot {@code h}) at {@code T} — the engine's
     * {@code orge_enthalpy::p_sat}: integrated Clausius–Clapeyron with Kirchhoff's {@code L(T)} from the pair's
     * existing columns, {@code ln(p/P_atm) = (M_h/R)·[A₀·(1/T_b − 1/T) − Δcp·ln(T/T_b)]}, {@code A₀ = L + Δcp·T_b},
     * {@code p_sat(T_b) = P_atm}; clamped finite.
     */
    static double pSat(Material c, Material h, double t) {
        double tb = c.maxTemp();
        double dcp = (double) c.heatCapacity() - h.heatCapacity();
        double a0 = c.latentHeatMax() + dcp * tb;
        double a = Math.max(1e-6, h.molarMass()) / R_GAS;
        double tc = Math.max(1.0, Math.min(1e5, t));
        double e = a * (a0 * (1.0 / tb - 1.0 / tc) - dcp * Math.log(tc / tb));
        return P_ATM * Math.exp(Math.max(-700.0, Math.min(50.0, e)));
    }

    /** {@code Σ_k (1−χ_k)·m_k/ρ_p,k} [m³]: the condensed volume of a cell's components. */
    private static double condensedVolume(SectionData d, int i, Function<Identifier, Material> lookup) {
        double v = 0.0;
        for (int k = 0; k < SectionData.SLOTS; k++) {
            Material m = lookup.apply(slotId(d, k, i));
            if (m != null) v += (1.0 - chi(m)) * Math.max(0f, slotMass(d, k, i)) / particleDensity(m);
        }
        return v;
    }

    /** The compressibility weight χ = (max − default)/(max − min + ε), as the engine computes it. */
    private static double chi(Material m) {
        float x = (m.maxMass() - m.defaultMass()) / (m.maxMass() - m.minMass() + 1e-6f);
        return Math.max(0f, Math.min(1f, x));
    }

    /** ρ_p with the law v5 #8 default ({@code = defaultMass}), as the engine reads it. */
    private static double particleDensity(Material m) {
        return Math.max(Math.max(m.particleDensity(), m.defaultMass()), 1e-6f);
    }

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    /** The pass's dither {@code u ∈ [0, 1)}: splitmix64 of the pass and the dimension (the same for every face). */
    private static double dither(long pass, Identifier dim) {
        long z = pass ^ ((long) dim.hashCode() << 32);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /** The slot of cell {@code i} holding {@code species} with mass, else -1 (a test on stored ids). */
    private static int slotOf(SectionData d, int i, Identifier species) {
        for (int k = 0; k < SectionData.SLOTS; k++) {
            if (species.equals(slotId(d, k, i)) && slotMass(d, k, i) > 0f) return k;
        }
        return -1;
    }

    private static void setSlot(SectionData d, int k, int i, Identifier id, float m) {
        if (k == 0) {
            d.setMaterialAt(i, id);
            d.setMass(i, m);
        } else {
            d.setSlot(k, i, id, m);
        }
    }

    private static Identifier slotId(SectionData d, int k, int i) {
        return k == 0 ? d.materialAt(i) : d.slotMaterialAt(k, i);
    }

    private static float slotMass(SectionData d, int k, int i) {
        return k == 0 ? d.massAt(i) : d.slotMassAt(k, i);
    }
}
