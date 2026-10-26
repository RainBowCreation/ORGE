package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Rain mass seeding (spec 2026-10-25-rain-mass-seeding, issue #33), the second {@link SkyColumnPass}
 * visitor: it only ever rides the forcing's walk, so rain costs no second heightmap scan. Each {@code (x, z)}
 * cell column's rain {@code dm = I·dt·Σw} lands on the cell of highest exposure {@code w} — air
 * ({@code ε = 0}) is never it, the first condensed cell is, and a one-block roof keeps the floor below dry
 * (a grey-body share would leak its {@code 1 − ε} through) — into its composition (law v5 #7): pore water in
 * sand / dirt, a free-water component on stone, the pool's own slot on water. Nothing classifies the cell;
 * what the water does next is the engine's, from the cell's own data.
 *
 * <p>The rain enters at {@link #RAIN_TEMP_K}: the cell's {@code E} gains {@code dm·h_water(T_rain)}. A cell
 * whose four slots all hold other species has no room and takes nothing (law #9: clamped, never dropped).
 * Every stored kilogram and joule is a boundary source booked per dimension in {@link #mass} and
 * {@link #energy}, so grand + per-species mass and grand energy are exact with the ledgers.
 * Server-thread, like the store it writes.</p>
 */
public final class RainSeeding {

    /** Rain intensity [kg/(m²·s)]: 5.1 mm/h, the middle of the AMS "moderate rain" band 2.6–7.6 mm/h. */
    public static final double RAIN_INTENSITY = 5.1 / 3600.0;

    /** Rain temperature [K]: the wet bulb of near-saturated air under rain ≈ the 14.0 °C global mean
     *  surface air temperature (Gosnell, Fairall &amp; Webster 1995; Jones et al. 1999). */
    public static final double RAIN_TEMP_K = 287.15;

    /** The water rain is made of. */
    public static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");

    /** Cumulative rain water [kg] seeded per dimension (the only seeded species). */
    private final Map<Identifier, Double> mass = new HashMap<>();
    /** Cumulative enthalpy [J] the rain brought per dimension. */
    private final Map<Identifier, Double> energy = new HashMap<>();

    /** Add {@code pass}'s ledgers (one sky pass run elsewhere, issue #51) into this one's. */
    public void absorb(RainSeeding pass) {
        pass.mass.forEach((dim, v) -> mass.merge(dim, v, Double::sum));
        pass.energy.forEach((dim, v) -> energy.merge(dim, v, Double::sum));
    }

    /** Rain water [kg] seeded into {@code dim} so far (0 when it never rained there). */
    public double mass(Identifier dim) {
        return mass.getOrDefault(dim, 0.0);
    }

    /** Enthalpy [J] the rain brought into {@code dim} so far. */
    public double energy(Identifier dim) {
        return energy.getOrDefault(dim, 0.0);
    }

    /**
     * The rain of {@code climate} over {@code dtSeconds} as a {@link SkyColumnPass.Visitor} for {@code dim}.
     * The caller re-puts the sections it was handed ({@link SkyColumnPass#walkAndPut}).
     */
    public SkyColumnPass.Visitor visitor(Identifier dim, SurfaceForcing.Climate climate, double dtSeconds,
                                         Function<Identifier, Material> lookup) {
        Material water = lookup.apply(WATER);
        double hRain = water == null ? 0.0 : EnthalpyCurve.hOf(water, lookup, (float) RAIN_TEMP_K);
        // Top face A = 1 m²; no water in the active material table means no rain to make.
        double perExposure = water == null ? 0.0 : climate.rainKgPerM2s() * dtSeconds;
        return SkyColumnPass.surface((key, data, cell, sumW) -> {
            float dm = (float) (perExposure * sumW);
            if (dm > 0f) {
                land(dim, data, cell, dm, hRain);
            }
        });
    }

    /** Seed {@code dm} into cell {@code i} at {@code hRain} per kg, booking what was stored. */
    private void land(Identifier dim, SectionData data, int i, float dm, double hRain) {
        double booked = seed(data, i, dm);
        if (booked == 0.0) {
            return; // no room
        }
        double before = data.enthalpyAt(i);
        double after = before + booked * hRain;
        data.setEnthalpy(i, after);
        mass.merge(dim, booked, Double::sum);
        energy.merge(dim, after - before, Double::sum);
    }

    /**
     * Add {@code dm} of water to cell {@code i}: into the slot already holding water, else the first empty
     * slot, else nowhere. Returns the stored float delta [kg] (0 when there is no room).
     */
    static double seed(SectionData data, int i, float dm) {
        int target = -1;
        for (int k = 0; k < SectionData.SLOTS && target < 0; k++) {
            if (WATER.equals(slotId(data, k, i)) && slotMass(data, k, i) > 0f) {
                target = k;
            }
        }
        for (int k = 0; k < SectionData.SLOTS && target < 0; k++) {
            if (slotMass(data, k, i) == 0f) {
                target = k;
            }
        }
        if (target < 0) {
            return 0.0; // four foreign species: no room for water
        }
        float before = slotMass(data, target, i);
        float after = before + dm;
        if (target == 0) {
            data.setMaterialAt(i, WATER);
            data.setMass(i, after);
        } else {
            data.setSlot(target, i, WATER, after);
        }
        return (double) after - before;
    }

    private static Identifier slotId(SectionData d, int k, int i) {
        return k == 0 ? d.materialAt(i) : d.slotMaterialAt(k, i);
    }

    private static float slotMass(SectionData d, int k, int i) {
        return k == 0 ? d.massAt(i) : d.slotMassAt(k, i);
    }
}
