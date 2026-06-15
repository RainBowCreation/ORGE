package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;

import java.util.function.Function;

/**
 * Spec §8.1 / law §6 chain-anchored enthalpy curve — the Java mirror of the engine's {@code orge_enthalpy}
 * (sim_engine.hpp §131-220). {@code E = m·h(T)}; {@code h(T)} is piecewise-linear in T (slope = heat
 * capacity), single-valued at a threshold (the forward map returns the plateau BOTTOM); the latent-heat
 * plateau of width {@code L} lives ONLY in the inverse {@code T = h⁻¹(E/m)}: a cell whose specific enthalpy
 * {@code η = E/m} sits in a threshold's latent band is PINNED at {@code T*} (mushy, paying latent heat);
 * past the band, T resumes on the material's own slope.
 *
 * <p><b>The source of truth is always {@code E} (= m·h), never T.</b> On a plateau T does not determine η,
 * so every relabel / cargo derive works from stored {@code E}; {@link #hOf} is for the genuinely
 * off-plateau case or a seed/boundary T only.
 *
 * <p><b>Chain anchoring (normative — without it a relabel fabricates MJ-scale E):</b> phase-paired curves
 * share one reference along the cold chain, so {@code h_target(T*) ≡ h_donor(T*) + L} at each plateau's far
 * edge ⇒ a relabel is the identity on {@code E} ({@code ΔE ≡ 0} by construction) and T is continuous across
 * every transition. A material is anchored against its COLDER phase (its {@code minTarget}) at its own
 * {@code minTemp}; a material with NO {@code minTarget} is a chain ROOT — anchor {@code (0,0)}, so
 * {@code h(T)=cp·T} (the legacy single slope). The walk strictly descends to a colder phase each hop
 * (chain length ≤ 3), so it terminates with no cycle and is side-effect-free.
 *
 * <p><b>Decision record:</b> this Java duplication of the engine curve is sanctioned ONLY because it is
 * spec-§8.1-derived AND parity-locked against the engine ({@code EnthalpyCurveTest}). The lookup is a pure
 * {@code Function<Identifier, Material>} (returns {@code null} for an absent / unresolvable id), which both
 * derive sites already hold via {@code registry::get} ({@code id -> registry.get(id).orElse(null)}); a
 * {@code null} colder phase is treated like a missing {@code minTarget}, i.e. the material becomes a ROOT.
 *
 * <p>All arithmetic is {@code double} internally; the cast to {@code float} happens only at the return of
 * {@link #deriveT} / {@link #tOfEta}, so {@code ENCODE(E=m·h(T)) → DECODE(T)} round-trips to within a float
 * ulp. The class is final with a private constructor — pure, no state, no I/O.
 */
public final class EnthalpyCurve {

    private EnthalpyCurve() {}

    /** The (T_anchor, h_anchor) pair for a material's curve: {@code h(T) = h_anchor + cp·(T − T_anchor)}. */
    private record CurveAnchor(double tAnchor, double hAnchor) {}

    /**
     * The curve anchor for {@code m}, walking the cold-phase chain (recursive; strictly descends, ≤ 3 hops).
     * ROOT (no resolvable {@code minTarget}) ⇒ {@code (0, 0)} so {@code h(T) = cp·T} (matches the engine
     * bit-for-bit for non-phase rows).
     */
    /** Sentinel signalling a cyclic LUT was detected below this frame — fail the WHOLE walk closed to ROOT. */
    private static final CurveAnchor CYCLE = new CurveAnchor(Double.NaN, Double.NaN);

    private static CurveAnchor curveAnchor(Material m, Function<Identifier, Material> lookup) {
        CurveAnchor ca = curveAnchor(m, lookup, 0);
        // A cycle anywhere along the chain collapses the entire material to ROOT (single slope cp·T) — we
        // can't trust ANY accumulated latent offset once the chain is non-terminating.
        return (ca == CYCLE) ? new CurveAnchor(0.0, 0.0) : ca;
    }

    /**
     * Depth-bounded recursion. Spec §8.1 caps the cold chain at ≤ 3 hops, so a cap of 8 never trips for
     * valid data; it fails closed to ROOT {@code (0,0)} only on a cyclic / self-referential datapack LUT
     * (e.g. {@code minTarget} resolving back to self, or A→B→A), guaranteeing termination instead of a
     * StackOverflow. The {@link #CYCLE} sentinel propagates up so the whole material degrades to ROOT
     * (not a partially-offset anchor). A self-target is just the degenerate cycle — caught by the same cap.
     */
    private static CurveAnchor curveAnchor(Material m, Function<Identifier, Material> lookup, int depth) {
        if (depth > 8) {
            return CYCLE; // cyclic LUT: fail closed to ROOT (single slope), propagated up the stack
        }
        Identifier coldId = m.minTarget();
        Material colder = (coldId == null) ? null : lookup.apply(coldId);
        if (colder == null) {
            return new CurveAnchor(0.0, 0.0); // ROOT: legacy single slope
        }
        double tStar = m.minTemp();
        CurveAnchor ca = curveAnchor(colder, lookup, depth + 1);
        if (ca == CYCLE) {
            return CYCLE; // a cycle below: don't add this frame's offset, just propagate ROOT-collapse up
        }
        double hColderAtStar = ca.hAnchor() + (double) colder.heatCapacity() * (tStar - ca.tAnchor());
        // This material sits L above its colder phase at the shared threshold (latent released on freeze).
        return new CurveAnchor(tStar, hColderAtStar + (double) m.latentHeatMin());
    }

    /**
     * Forward specific enthalpy {@code h(T)} [J/kg], single-valued (returns the plateau BOTTOM at a
     * threshold). Pure.
     */
    public static double hOf(Material m, Function<Identifier, Material> lookup, float T) {
        CurveAnchor a = curveAnchor(m, lookup);
        return a.hAnchor() + (double) m.heatCapacity() * ((double) T - a.tAnchor());
    }

    /**
     * Inverse temperature from specific enthalpy {@code η = E/m} [J/kg], WITH latent plateaus.
     * Returns {@code fallbackT} when {@code cp <= 0} (VOID / no substance).
     */
    public static float tOfEta(Material m, Function<Identifier, Material> lookup, double eta, float fallbackT) {
        double cp = (double) m.heatCapacity();
        if (cp <= 0.0) {
            return fallbackT; // VOID / no substance
        }
        CurveAnchor a = curveAnchor(m, lookup);
        // Affine inverse on this material's own slope.
        double T = a.tAnchor() + (eta - a.hAnchor()) / cp;

        // NOTE: the anchor chain walks ONLY the cold side (minTarget); the hot-side band below is layered
        // above h(maxTemp) on this same curve, not by walking maxTarget.
        // maxTemp plateau (HEATING into the hotter phase, e.g. water boil at 373): the latent band sits
        // ABOVE h(maxTemp) = [h(T*), h(T*)+latentHeatMax], pinned at T*. Above the band, subtract L so T
        // resumes on the slope. (No maxTarget => no plateau on this side.)
        if (m.maxTarget() != null && m.latentHeatMax() > 0f) {
            double tStar = m.maxTemp();
            double hStar = a.hAnchor() + cp * (tStar - a.tAnchor());
            double L = (double) m.latentHeatMax();
            if (eta >= hStar && eta <= hStar + L) {
                T = tStar; // pinned (mushy)
            } else if (eta > hStar + L) {
                T = tStar + (eta - hStar - L) / cp;
            }
        }
        // minTemp plateau (COOLING into the colder phase, e.g. water freeze / steam condense at T*): the
        // latent band sits BELOW h(minTemp) = [h(T*)-latentHeatMin, h(T*)], pinned at T*. Below the band,
        // T resumes on the slope (eta < band bottom => T < T*). (No minTarget => no plateau on this side.)
        if (m.minTarget() != null && m.latentHeatMin() > 0f) {
            double tStar = m.minTemp();
            double hStar = a.hAnchor() + cp * (tStar - a.tAnchor()); // == h_anchor (T*==T_anchor)
            double L = (double) m.latentHeatMin();
            if (eta <= hStar && eta >= hStar - L) {
                T = tStar; // pinned (mushy)
            } else if (eta < hStar - L) {
                T = tStar + (eta - (hStar - L)) / cp;
            }
        }
        return (float) T;
    }

    /**
     * Cell enthalpy {@code E = mass·h(T)} [J] (the forward encode). Reduces bit-identically to
     * {@code mass·(cp·T)} for a chain-root material (anchor {@code (0,0)}).
     */
    public static double cellE(float mass, Material m, Function<Identifier, Material> lookup, float T) {
        return (double) mass * hOf(m, lookup, T);
    }

    /**
     * Derived temperature from stored enthalpy {@code E} and {@code mass} (the inverse decode). Returns
     * {@code fallbackT} when {@code mass <= 0} (massless ⇒ no enthalpy) or {@code cp <= 0} (VOID).
     */
    public static float deriveT(double E, float mass, Material m, Function<Identifier, Material> lookup, float fallbackT) {
        if (mass <= 0f) {
            return fallbackT; // massless -> no enthalpy
        }
        return tOfEta(m, lookup, E / (double) mass, fallbackT);
    }
}
