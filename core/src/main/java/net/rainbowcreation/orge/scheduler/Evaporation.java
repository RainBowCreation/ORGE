package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Daytime evaporation (spec 2026-10-25-daytime-evaporation, issue #34), the third {@link SkyColumnPass}
 * visitor: it rides the forcing's walk, so it costs no extra scan. By day and while it is not raining, each
 * {@code (x, z)} column's surface cell — the one rain lands on — gives water to the cell directly above it,
 * where it lands as vapor ({@code water.maxTarget}, a {@code steam} component of the air). Free water and pore
 * water evaporate by one rule; only the surface does, so underground water is never touched.
 *
 * <p>The rate is the engine's #28 vapor exchange across that one face ({@code engine_b.hpp} vapor exchange):
 * {@code F = χ_pair·g_vap·A·dt·max(0, w_liq·w_gas·(p_sat(T_donor) − p_v))} — the biome enters through the
 * water's own derived {@code T}. Clamped by the donor's cohesive floor (law #9), by the receiver's saturation
 * (one pass never lifts {@code p_v} past {@code p_sat}) and its slot room (four foreign species: 0, clamped,
 * never dropped). The vapor carries {@code h_steam(T_donor)}: the donor pays the latent heat.</p>
 *
 * <p>Both float masses change by the same {@code x} on the face's mass lattice (dithered, like #28's
 * {@code vap_quantize}), so H₂O is exact to the bit. Vapor with no simulated cell above leaves the domain: a
 * boundary sink booked in {@link #mass} / {@link #energy}, which also take the sub-ulp rounding of the energy
 * transfer, so {@code Σ E + ledgers} is exact. Server-thread, like the store it writes.</p>
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
    /** The moles of a 1 mm film of gas at P_atm and 288 K (engine {@code vapk::N_FILM}). */
    static final double N_FILM = P_ATM * V_FILM / (R_GAS * 288.0);
    private static final double V = 1.0;    // cell volume [m³]
    private static final double A = 1.0;    // top face [m²]

    /** Vapor that left the domain [kg] per dimension (≤ 0). */
    private final Map<Identifier, Double> mass = new HashMap<>();
    /** Enthalpy that left with it, plus the energy transfer's rounding residue [J] per dimension. */
    private final Map<Identifier, Double> energy = new HashMap<>();
    /** All water evaporated off surfaces [kg] per dimension (diagnostic: into the air or out of the domain). */
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

    /** Water [kg] evaporated off {@code dim}'s surfaces so far. */
    public double evaporated(Identifier dim) {
        return evaporated.getOrDefault(dim, 0.0);
    }

    /**
     * The evaporation of {@code climate} over {@code dtSeconds} as a {@link SkyColumnPass.Visitor} for {@code dim}.
     * Nothing evaporates unless the sun is up and no rain falls (the climate, not a material, decides). A
     * receiver in another section of {@code store} is re-{@code put} here; the walk's own sections by the caller
     * ({@link SkyColumnPass#walkAndPut}).
     */
    public SkyColumnPass.Visitor visitor(Identifier dim, SurfaceForcing.Climate climate, double dtSeconds,
                                         SectionStore store, Function<Identifier, Material> lookup) {
        return visitor(dim, climate, dtSeconds, store, lookup, nextDither(dim, climate, lookup));
    }

    /**
     * This pass's dither for {@code dim}, advancing the pass count — once per dimension per sky pass, and
     * only when the pass can evaporate at all — or {@code NaN} when it cannot. Taken on the thread that owns
     * this ledger, for a {@link #visitor(Identifier, SurfaceForcing.Climate, double, SectionStore, Function,
     * double)} that may run elsewhere (issue #51).
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
                                         SectionStore store, Function<Identifier, Material> lookup, double u) {
        Material water = lookup.apply(WATER);
        Material vapor = water == null || water.maxTarget() == null ? null : lookup.apply(water.maxTarget());
        if (Double.isNaN(u) || water == null || vapor == null) {
            return (key, data, cell, w) -> { };
        }
        double k = Math.max(chi(water), chi(vapor)) * G_VAP_PENMAN * A * dtSeconds;
        return SkyColumnPass.surface((key, data, cell, sumW) ->
                evaporate(dim, store, key, data, cell, water, vapor, k, u, lookup));
    }

    /** Add {@code pass}'s ledgers (one sky pass run elsewhere, issue #51) into this one's. */
    public void absorb(Evaporation pass) {
        pass.mass.forEach((dim, v) -> mass.merge(dim, v, Double::sum));
        pass.energy.forEach((dim, v) -> energy.merge(dim, v, Double::sum));
        pass.evaporated.forEach((dim, v) -> evaporated.merge(dim, v, Double::sum));
    }

    /** A cell of the store: {@code data} holds it at section cell index {@code cell}. */
    private record At(SubchunkKey key, SectionData data, int cell) { }

    private void evaporate(Identifier dim, SectionStore store, SubchunkKey key, SectionData data, int cell,
                           Material water, Material vapor, double k, double u,
                           Function<Identifier, Material> lookup) {
        int kc = slotOf(data, cell, water.id());
        if (kc < 0) {
            return; // no water on this surface
        }
        float mc = slotMass(data, kc, cell);
        double vc = (1.0 - chi(water)) * mc / particleDensity(water);
        double ta = CellTemperature.derived(data, cell, lookup);
        double pSat = pSat(water, vapor, ta);
        double ac = clamp01(1.0 - (condensedVolume(data, cell, lookup) - vc) / V);
        double budget = Math.max(0.0, mc - (1.0 - chi(water)) * ac * water.minMass());

        At r = above(store, key, data, cell);
        double wGas = 1.0;      // no simulated cell above: the free atmosphere
        double pv = 0.0;
        double limit = budget;
        int kh = -1;
        float mh = 0f;
        if (r != null) {
            kh = slotOf(r.data(), r.cell(), vapor.id());
            kh = kh >= 0 ? kh : emptySlot(r.data(), r.cell());
            if (kh < 0) {
                return; // four foreign species: no room for the vapor
            }
            mh = slotMass(r.data(), kh, r.cell());
            double tb = CellTemperature.derived(r.data(), r.cell(), lookup);
            double vh = (1.0 - chi(vapor)) * mh / particleDensity(vapor);
            double vFree = Math.max(1e-6 * V, V - (condensedVolume(r.data(), r.cell(), lookup) - vh));
            wGas = clamp01(gasMoles(r.data(), r.cell(), lookup) / N_FILM);
            pv = mh / Math.max(1e-12, vapor.molarMass()) * R_GAS * tb / vFree;
            double toSaturation = Math.max(0.0, pSat - pv) * vFree * vapor.molarMass() / (R_GAS * tb);
            limit = Math.min(limit, Math.min(toSaturation, Math.max(0.0, vapor.maxMass() - mh)));
        }
        double f = Math.min(limit, k * Math.max(0.0, clamp01(vc / V_FILM) * wGas * (pSat - pv)));
        if (!(f > 0.0)) {
            return;
        }
        // The face's mass lattice: a power of two ≥ the float spacing of both slot values, so both stores
        // change by exactly x; the sub-lattice remainder is dithered (unbiased over passes).
        double q = Math.max(Math.ulp(mc), Math.ulp((float) (mh + f)));
        double x = Math.min(q * Math.floor(f / q + u), q * Math.floor(limit / q));
        // A few steps down suffice; a receiver crossing a binade with its lowest bit set has no exact x this
        // pass (it waits for its mass to change) rather than moving H₂O inexactly.
        float c1 = 0f;
        float h1 = 0f;
        boolean exact = false;
        for (int tries = 0; tries < 4 && x > 0.0 && !exact; tries++) {
            c1 = (float) (mc - x);
            h1 = (float) (mh + x);
            exact = (double) mc - c1 == x && (r == null || ((double) h1 - mh == x && (double) h1 - x == mh));
            x = exact ? x : x - q;
        }
        if (!exact) {
            return;
        }

        double hv = EnthalpyCurve.hOf(vapor, lookup, (float) ta);
        float massA = data.cellMassAt(cell);
        setSlot(data, kc, cell, c1 > 0f ? water.id() : MaterialPalette.VACUUM_ID, c1);
        double ea0 = data.enthalpyAt(cell);
        data.setEnthalpy(cell, ea0 - x * hv);
        double dA = data.enthalpyAt(cell) - ea0;
        // The vapor leaves at the donor's velocity: the donor keeps v, the receiver gains x·u.
        float keep = data.cellMassAt(cell) / massA;
        float px = data.momXAt(cell);
        float py = data.momYAt(cell);
        float pz = data.momZAt(cell);
        data.setMomentum(cell, px * keep, py * keep, pz * keep);
        evaporated.merge(dim, x, Double::sum);
        if (r == null) {
            mass.merge(dim, -x, Double::sum);
            energy.merge(dim, dA, Double::sum);
            return;
        }
        setSlot(r.data(), kh, r.cell(), vapor.id(), h1);
        double eb0 = r.data().enthalpyAt(r.cell());
        r.data().setEnthalpy(r.cell(), eb0 - dA);
        double residue = (r.data().enthalpyAt(r.cell()) - eb0) + dA;
        if (residue != 0.0) {
            energy.merge(dim, residue, Double::sum);
        }
        float out = 1f - keep;
        r.data().setMomentum(r.cell(), r.data().momXAt(r.cell()) + px * out,
                r.data().momYAt(r.cell()) + py * out, r.data().momZAt(r.cell()) + pz * out);
        if (!r.key().equals(key)) {
            store.put(r.key(), r.data()); // the walk re-puts only the sections it handed out
        }
    }

    /** The cell directly above {@code cell}: in its own section, or in the stored section above; else none. */
    private static At above(SectionStore store, SubchunkKey key, SectionData data, int cell) {
        if (((cell >> 4) & 15) < 15) {
            return new At(key, data, cell + 16);
        }
        SubchunkKey up = new SubchunkKey(key.cx(), key.sectionY() + 1, key.cz());
        if (up.sectionY() > ColumnAssembler.MAX_SECTION_Y || !store.hasSection(up)) {
            return null; // outside the simulated domain
        }
        return new At(up, store.get(up), cell - 15 * 16);
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

    /** {@code n_gas = Σ_k χ_k·m_k/M_k} [mol]: the cell's gas. */
    private static double gasMoles(SectionData d, int i, Function<Identifier, Material> lookup) {
        double n = 0.0;
        for (int k = 0; k < SectionData.SLOTS; k++) {
            Material m = lookup.apply(slotId(d, k, i));
            if (m != null) n += chi(m) * Math.max(0f, slotMass(d, k, i)) / Math.max(1e-12, m.molarMass());
        }
        return n;
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

    private static int emptySlot(SectionData d, int i) {
        for (int k = 0; k < SectionData.SLOTS; k++) {
            if (slotMass(d, k, i) == 0f) return k;
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
