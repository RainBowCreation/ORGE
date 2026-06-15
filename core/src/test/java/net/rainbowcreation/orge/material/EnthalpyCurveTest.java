package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §8.1 chain-anchored enthalpy curve, asserted against hand-computed reference points (which are
 * exactly what the engine's {@code orge_enthalpy} (sim_engine.hpp lines 131-220) produces — see the
 * off-plateau parity cross-check). The helper is pure and takes a material lookup so the chain walk
 * resolves {@code minTarget}.
 *
 * <p>Reference material set (spec §1.2, hermetic — built via {@link Material.Builder}):
 * <ul>
 *   <li>ice (chain ROOT, no minTarget): cp=2108</li>
 *   <li>water: cp=4186, minTemp=273→ice L=3.34e5, maxTemp=373→steam L=2.256e6</li>
 *   <li>steam: cp=2080, minTemp=373→water L=2.256e6</li>
 * </ul>
 */
class EnthalpyCurveTest {

    private static final Identifier ICE   = Identifier.fromNamespaceAndPath("orge", "ice");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");

    private static final float L_FREEZE = 3.34e5f; // water<->ice latent (J/kg)
    private static final float L_BOIL   = 2.256e6f; // water<->steam latent (J/kg)

    private static Material ice() {
        // ROOT for the cold side: no minTarget => anchor (0,0), h(T)=cp*T.
        return Material.builder(ICE)
                .thermalConductivity(2.2f).heatCapacity(2108f).molarMass(0.018f)
                .defaultMass(917f).defaultTemperature(Float.NaN).viscosity(Float.POSITIVE_INFINITY)
                .maxTemp(273f).maxTarget(WATER).latentHeatMax(L_FREEZE)
                .build();
    }

    private static Material water() {
        return Material.builder(WATER)
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN).viscosity(1.0e-3f)
                .minMass(125f).maxMass(1000f)
                .minTemp(273f).minTarget(ICE).latentHeatMin(L_FREEZE)
                .maxTemp(373f).maxTarget(STEAM).latentHeatMax(L_BOIL)
                .build();
    }

    private static Material steam() {
        return Material.builder(STEAM)
                .thermalConductivity(0.025f).heatCapacity(2080f).molarMass(0.018f)
                .defaultMass(0.6f).defaultTemperature(Float.NaN).viscosity(1.3e-5f)
                .minMass(0.06f).maxMass(1000f).tRefGas(373f)
                .minTemp(373f).minTarget(WATER).latentHeatMin(L_BOIL)
                .build();
    }

    private static Function<Identifier, Material> lookup() {
        Map<Identifier, Material> byId = new HashMap<>();
        Material ice = ice(), water = water(), steam = steam();
        byId.put(ICE, ice);
        byId.put(WATER, water);
        byId.put(STEAM, steam);
        return byId::get;
    }

    // ---- (a) ROOT identity: no minTarget => h(T)=cp*T exactly; deriveT round-trips off-plateau. ----
    @Test
    void rootIsLegacySingleSlope() {
        Function<Identifier, Material> lut = lookup();
        Material ice = ice();
        float T = 250f; // off the (only) plateau side; ice has no minTarget plateau
        double e = EnthalpyCurve.cellE(1.0f, ice, lut, T); // m=1 => E == h(T)
        assertEquals(2108.0 * 250.0, e, 1e-3, "ROOT h(T)=cp*T");
        assertEquals(T, EnthalpyCurve.deriveT(e, 1.0f, ice, lut, Float.NaN), 1e-2f, "ROOT round-trip");
    }

    // ---- (b) Water off-plateau warming at T=350 with the HAND-COMPUTED anchor. ----
    @Test
    void waterOffPlateauWarming() {
        Function<Identifier, Material> lut = lookup();
        Material water = water();
        // ice ROOT ca=(0,0); h_anchor = ice.cp*273 + water.latentHeatMin
        double hAnchor = 2108.0 * 273.0 + 3.34e5;
        double T = 350.0; // off any plateau (273<350<373)
        double h350 = hAnchor + 4186.0 * (T - 273.0);
        double mass = 7.0;
        double E = mass * h350;
        assertEquals(350f, EnthalpyCurve.deriveT(E, (float) mass, water, lut, Float.NaN), 1e-2f);
        // and cellE forward agrees
        assertEquals(E, EnthalpyCurve.cellE((float) mass, water, lut, 350f), 1e-3, "forward cellE");
    }

    // ---- (c) BOIL plateau degeneracy at T*=373: band [h_star, h_star+L_boil] all pin to 373. ----
    @Test
    void boilPlateauPinsAt373() {
        Function<Identifier, Material> lut = lookup();
        Material water = water();
        double hAnchor = 2108.0 * 273.0 + 3.34e5;
        double hStar = hAnchor + 4186.0 * (373.0 - 273.0); // h(373)
        double mass = 1000.0;
        // bottom edge
        assertEquals(373f, EnthalpyCurve.deriveT(mass * hStar, (float) mass, water, lut, Float.NaN), 1e-3f);
        // mid-band
        assertEquals(373f, EnthalpyCurve.deriveT(mass * (hStar + 1.128e6), (float) mass, water, lut, Float.NaN), 1e-3f);
        // top edge
        assertEquals(373f, EnthalpyCurve.deriveT(mass * (hStar + 2.256e6), (float) mass, water, lut, Float.NaN), 1e-3f);
        // just ABOVE the band: resumes on slope at ~383
        double etaAbove = hStar + 2.256e6 + 4186.0 * 10.0;
        assertEquals(383f, EnthalpyCurve.deriveT(mass * etaAbove, (float) mass, water, lut, Float.NaN), 1e-2f);
    }

    // ---- (d) FREEZE plateau: band [h_anchor - L_freeze, h_anchor] pins at 273; just below -> T<273. ----
    @Test
    void freezePlateauPinsAt273() {
        Function<Identifier, Material> lut = lookup();
        Material water = water();
        double hAnchor = 2108.0 * 273.0 + 3.34e5; // = h(273)
        double mass = 1000.0;
        // top edge (= h_anchor) pins
        assertEquals(273f, EnthalpyCurve.deriveT(mass * hAnchor, (float) mass, water, lut, Float.NaN), 1e-3f);
        // bottom edge (h_anchor - L_freeze) pins
        assertEquals(273f, EnthalpyCurve.deriveT(mass * (hAnchor - 3.34e5), (float) mass, water, lut, Float.NaN), 1e-3f);
        // just BELOW the band: resumes on slope, T < 273
        double etaBelow = (hAnchor - 3.34e5) - 4186.0 * 5.0; // 5 K below the slope re-entry
        assertEquals(268f, EnthalpyCurve.deriveT(mass * etaBelow, (float) mass, water, lut, Float.NaN), 1e-2f);
    }

    // ---- (e) CHAIN ΔE≡0 / continuity: h_water(273) - h_ice(273) == L_freeze exactly. ----
    @Test
    void chainContinuityAtSharedThreshold() {
        Function<Identifier, Material> lut = lookup();
        Material water = water(), ice = ice();
        double hWater273 = EnthalpyCurve.hOf(water, lut, 273f);
        double hIce273 = EnthalpyCurve.hOf(ice, lut, 273f);
        assertEquals(3.34e5, hWater273 - hIce273, 1e-3, "h_water(273) - h_ice(273) == L_freeze (ΔE≡0)");

        // Relabel identity: a 273 K water cell relabeled to ice at the SAME E keeps T=273 (no jump).
        // water at 273 sits at the bottom of the boil-side slope and the top of the freeze band; its
        // specific enthalpy on the water curve at exactly the freeze top-edge is hWater273. The ice
        // curve reads that same E (carried mass) back to 273.
        double mass = 1000.0;
        double E = mass * hWater273;          // 273 K water cell, top of freeze band
        // ice's max-side plateau is at 273 (ice->water L=L_freeze); E at ice's h(273)+0 pins to 273.
        assertEquals(273f, EnthalpyCurve.deriveT(E, (float) mass, ice, lut, Float.NaN), 1e-3f,
                "relabel water(273)->ice keeps T=273, ΔE≡0");
    }

    // ---- (f) Engine off-plateau cross-check: explicit (E, mass, material) triples, hand-computed T. ----
    @Test
    void engineOffPlateauCrossCheck() {
        Function<Identifier, Material> lut = lookup();
        Material water = water(), ice = ice(), steam = steam();
        double hAnchorWater = 2108.0 * 273.0 + 3.34e5;

        // Triple 1: ice at 200 K, mass 917 (ROOT slope). Equals C++ T_of_eta.
        double e1 = 917.0 * (2108.0 * 200.0);
        assertEquals(200f, EnthalpyCurve.deriveT(e1, 917f, ice, lut, Float.NaN), 1e-2f);

        // Triple 2: water at 290 K, mass 500 (anchored slope). Equals C++ T_of_eta.
        double h290 = hAnchorWater + 4186.0 * (290.0 - 273.0);
        double e2 = 500.0 * h290;
        assertEquals(290f, EnthalpyCurve.deriveT(e2, 500f, water, lut, Float.NaN), 1e-2f);

        // Triple 3: steam at 450 K, mass 0.6 — steam anchored against water at 373 + L_boil.
        // h_anchor_steam = h_water(373) + L_boil = (hAnchorWater + 4186*(373-273)) + 2.256e6
        double hAnchorSteam = (hAnchorWater + 4186.0 * (373.0 - 273.0)) + 2.256e6;
        double h450 = hAnchorSteam + 2080.0 * (450.0 - 373.0);
        double e3 = 0.6 * h450;
        assertEquals(450f, EnthalpyCurve.deriveT(e3, 0.6f, steam, lut, Float.NaN), 1e-2f);
    }

    // ---- VOID / cp<=0 returns fallback. ----
    @Test
    void voidReturnsFallback() {
        Function<Identifier, Material> lut = lookup();
        Material voidMat = Material.builder(Identifier.fromNamespaceAndPath("orge", "void"))
                .thermalConductivity(0f).heatCapacity(0f).molarMass(0f)
                .defaultMass(0f).defaultTemperature(Float.NaN).viscosity(0f)
                .minMass(0f).maxMass(0f)
                .build();
        assertEquals(288f, EnthalpyCurve.deriveT(123.0, 1.0f, voidMat, lut, 288f), 0f);
        // mass<=0 also returns fallback
        assertEquals(288f, EnthalpyCurve.deriveT(123.0, 0f, water(), lut, 288f), 0f);
    }

    // ---- (N5a) Cyclic / self-referential LUT must terminate (depth cap), not StackOverflow. ----
    @Test
    void cyclicLutDoesNotStackOverflow() {
        // A <-> B cycle: each material's minTarget resolves (via the lookup) to the other, forever.
        Identifier aId = Identifier.fromNamespaceAndPath("orge", "cyc_a");
        Identifier bId = Identifier.fromNamespaceAndPath("orge", "cyc_b");
        Material a = Material.builder(aId)
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN).viscosity(1.0e-3f)
                .minTemp(273f).minTarget(bId).latentHeatMin(L_FREEZE)
                .build();
        Material b = Material.builder(bId)
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN).viscosity(1.0e-3f)
                .minTemp(273f).minTarget(aId).latentHeatMin(L_FREEZE)
                .build();
        Map<Identifier, Material> byId = new HashMap<>();
        byId.put(aId, a);
        byId.put(bId, b);
        Function<Identifier, Material> lut = byId::get;

        // The point: every entry point TERMINATES (no StackOverflowError) and falls back to ROOT (0,0),
        // i.e. the depth-capped anchor degrades to single-slope cp*T for the off-cycle evaluation.
        float T = 350f;
        double h = EnthalpyCurve.hOf(a, lut, T);
        assertEquals(4186.0 * 350.0, h, 1e-3, "cyclic LUT degrades hOf to ROOT single-slope cp*T");
        assertEquals(350f, EnthalpyCurve.tOfEta(a, lut, h, Float.NaN), 1e-2f, "cyclic tOfEta terminates on slope");
        double E = 1000.0 * h;
        assertEquals(350f, EnthalpyCurve.deriveT(E, 1000f, a, lut, Float.NaN), 1e-2f,
                "cyclic deriveT terminates on slope");
    }

    // ---- (N5b) minTarget present but latentHeatMin == 0 => no plateau pin (the `&& L > 0f` guard). ----
    @Test
    void targetPresentButZeroLatentSkipsPlateau() {
        // water-like row but with a resolvable minTarget and ZERO latent: T must derive straight on the
        // slope through what would be the threshold, with NO plateau pin at 273.
        Identifier zId = Identifier.fromNamespaceAndPath("orge", "zero_latent");
        Material z = Material.builder(zId)
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN).viscosity(1.0e-3f)
                .minTemp(273f).minTarget(ICE).latentHeatMin(0f) // resolvable target, zero latent => no plateau
                .build();
        Map<Identifier, Material> byId = new HashMap<>();
        byId.put(ICE, ice());
        byId.put(zId, z);
        Function<Identifier, Material> lut = byId::get;

        // anchor: ice ROOT (0,0); h_anchor = ice.cp*273 + 0 = 2108*273. At eta = h(273) and just below it,
        // T derives straight on the slope (273 at the anchor, < 273 below) -- NO pin to 273.
        double hAnchor = 2108.0 * 273.0; // h(273), L=0 so no offset
        double mass = 1000.0;
        // exactly at the would-be threshold: 273 (anchor point, on the slope, not a plateau)
        assertEquals(273f, EnthalpyCurve.deriveT(mass * hAnchor, (float) mass, z, lut, Float.NaN), 1e-2f);
        // 5 K of slope below the threshold: with NO plateau, T = 268 straight on the slope.
        double etaBelow = hAnchor - 4186.0 * 5.0;
        assertEquals(268f, EnthalpyCurve.deriveT(mass * etaBelow, (float) mass, z, lut, Float.NaN), 1e-2f,
                "zero latent => no plateau pin, T resumes on slope below threshold");
    }
}
