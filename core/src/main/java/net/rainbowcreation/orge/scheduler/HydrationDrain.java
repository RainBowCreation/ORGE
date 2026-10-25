package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Thirsty farmland (spec 2026-10-25-thirsty-farmland §3): each hydrating farmland random tick queues a
 * request to draw {@link #drainKg()} of water from the source cell vanilla found; the scheduler applies the
 * queue while IDLE, before the snapshot, so no in-flight write-back can overwrite it and the next step
 * simulates the lowered water.
 *
 * <p>Per request the cell's first water slot (a movable material drawn as {@code minecraft:water}, free or
 * pore water) loses {@code Δm = min(request, m_k)}, the cell's {@code E} loses the drained water's own
 * enthalpy {@code Δm·h_k(T)} ({@code T} derived), its momentum scales with its mass (velocity unchanged),
 * and a slot drained to 0 becomes the empty slot {@code (orge:vacuum, 0)} — the reconciler then clears a
 * free source to air and draws a waterlogged host dry. The drain is a boundary sink: the {@link #massLedger}
 * and {@link #energyLedger} book the stored float differences per dimension, so {@code Σ m + ledger} (per
 * species) and {@code Σ E + ledger} are exact. Server-thread, like the store it writes.</p>
 */
public final class HydrationDrain {

    /** JVM property overriding {@link #DEFAULT_DRAIN_KG} [kg per hydrating random tick]; 0 turns it off. */
    public static final String DRAIN_PROPERTY = "orge.farmland.drainKg";

    /**
     * FAO-56 reference evapotranspiration ET₀ = 5 mm/day over the farmland's 1 m², for the mean interval
     * between one block's random ticks at the default {@code randomTickSpeed} 3 ({@code 4096/3} ticks at
     * 20 ticks/s): {@code ≈ 3.95e-3} kg, so a hydrated farmland drinks ET₀ on average.
     */
    public static final float DEFAULT_DRAIN_KG = (float) (5.0 / 86400.0 * (4096.0 / 3.0 / 20.0));

    private static final Identifier WATER_BLOCK = Identifier.fromNamespaceAndPath("minecraft", "water");

    /** One world cell. */
    private record Cell(int x, int y, int z) {}

    private final float drainKg;
    /** dim → cell → requested kg, accumulated in arrival order. */
    private final Map<Identifier, Map<Cell, Double>> queue = new LinkedHashMap<>();
    /** dim → species → cumulative boundary mass [kg]: − out of the world. */
    private final Map<Identifier, Map<Identifier, Double>> massLedger = new HashMap<>();
    /** dim → cumulative boundary energy [J]: − out of the world. */
    private final Map<Identifier, Double> energyLedger = new HashMap<>();

    public HydrationDrain(float drainKg) {
        this.drainKg = drainKg;
    }

    /** The drain configured by {@link #DRAIN_PROPERTY}. */
    public static HydrationDrain fromSystemProperty() {
        return new HydrationDrain(drainKgFrom(System.getProperty(DRAIN_PROPERTY)));
    }

    /** {@code value} as a non-negative kg amount; absent, unparsable or negative → {@link #DEFAULT_DRAIN_KG}. */
    static float drainKgFrom(String value) {
        if (value == null) {
            return DEFAULT_DRAIN_KG;
        }
        try {
            float kg = Float.parseFloat(value.trim());
            return kg >= 0f && Float.isFinite(kg) ? kg : DEFAULT_DRAIN_KG;
        } catch (NumberFormatException e) {
            return DEFAULT_DRAIN_KG;
        }
    }

    public float drainKg() {
        return drainKg;
    }

    /** Queue one hydration of the farmland whose source water is world block {@code (x, y, z)} of {@code dim}. */
    public void request(Identifier dim, int x, int y, int z) {
        queue.computeIfAbsent(dim, k -> new LinkedHashMap<>()).merge(new Cell(x, y, z), (double) drainKg, Double::sum);
    }

    /** Queued (not yet applied) source cells. */
    public int pending() {
        int n = 0;
        for (Map<Cell, Double> cells : queue.values()) n += cells.size();
        return n;
    }

    /** Drop every queued request (server stop). */
    public void clear() {
        queue.clear();
    }

    /** Cumulative boundary mass of {@code species} drained from {@code dim} [kg] (≤ 0). */
    public double massLedger(Identifier dim, Identifier species) {
        Map<Identifier, Double> bySpecies = massLedger.get(dim);
        return bySpecies == null ? 0.0 : bySpecies.getOrDefault(species, 0.0);
    }

    /** Cumulative boundary mass of every species drained from {@code dim} [kg] (≤ 0). */
    public double massLedger(Identifier dim) {
        Map<Identifier, Double> bySpecies = massLedger.get(dim);
        double sum = 0.0;
        if (bySpecies != null) for (double m : bySpecies.values()) sum += m;
        return sum;
    }

    /** Cumulative boundary energy drained from {@code dim} with the water [J]. */
    public double energyLedger(Identifier dim) {
        return energyLedger.getOrDefault(dim, 0.0);
    }

    /**
     * Apply and clear the queue. {@code storeFor} gives a dimension's store (null when it has none); a request
     * whose column is not loaded, whose section is not stored (its water is not simulated yet) or whose cell
     * holds no water drains nothing. Each drained section is re-{@code put} (column dirty) and its flow woken.
     */
    public void apply(Function<Identifier, SectionStore> storeFor, Function<Identifier, Material> lookup,
                      WakeSink wake) {
        for (Map.Entry<Identifier, Map<Cell, Double>> byDim : queue.entrySet()) {
            Identifier dim = byDim.getKey();
            SectionStore store = storeFor.apply(dim);
            if (store == null) {
                continue;
            }
            Map<SubchunkKey, SectionData> touched = new LinkedHashMap<>();
            for (Map.Entry<Cell, Double> req : byDim.getValue().entrySet()) {
                Cell c = req.getKey();
                SubchunkKey key = new SubchunkKey(c.x() >> 4, c.y() >> 4, c.z() >> 4);
                if (!store.isLoaded(key.cx(), key.cz()) || !store.hasSection(key)) {
                    continue;
                }
                SectionData data = store.get(key);
                int cell = (c.x() & 15) | (c.y() & 15) << 4 | (c.z() & 15) << 8;
                if (drainCell(dim, data, cell, req.getValue(), lookup)) {
                    touched.put(key, data);
                }
            }
            touched.forEach((key, data) -> {
                store.put(key, data);           // mark the column dirty so the next save keeps the drain
                wake.wakeFlowSection(dim, key); // a dormant section steps and reconciles the lowered water
            });
        }
        queue.clear();
    }

    /** Drain up to {@code requestKg} from the cell's first water slot; true when anything was drained. */
    private boolean drainCell(Identifier dim, SectionData data, int cell, double requestKg,
                              Function<Identifier, Material> lookup) {
        int k = -1;
        Material water = null;
        for (int s = 0; s < SectionData.SLOTS && k < 0; s++) {
            Material m = lookup.apply(slotMaterial(data, s, cell));
            if (m != null && drawsAsWater(m) && slotMass(data, s, cell) > 0f) {
                k = s;
                water = m;
            }
        }
        if (k < 0) {
            return false;
        }
        float before = slotMass(data, k, cell);
        float after = (float) Math.max(0.0, before - requestKg);
        double drained = (double) before - (double) after;
        if (!(drained > 0.0)) {
            return false;
        }
        float cellMass = data.cellMassAt(cell);
        float e0 = data.enthalpyAt(cell);
        double t = derivedT(data, cell, lookup);
        Identifier id = after > 0f ? water.id() : MaterialPalette.VACUUM_ID; // an empty slot is (orge:vacuum, 0)
        if (k == 0) {
            data.setMaterialAt(cell, id);
            data.setMass(cell, after);
        } else {
            data.setSlot(k, cell, id, after);
        }
        float remaining = data.cellMassAt(cell);
        float e1 = remaining > 0f ? (float) (e0 - drained * EnthalpyCurve.hOf(water, lookup, (float) t)) : 0f;
        data.setEnthalpy(cell, e1);
        float keep = remaining / cellMass; // the water leaves at the cell's velocity (cellMass > 0: it held water)
        data.setMomentum(cell, data.momXAt(cell) * keep, data.momYAt(cell) * keep, data.momZAt(cell) * keep);
        massLedger.computeIfAbsent(dim, d -> new HashMap<>()).merge(water.id(), -drained, Double::sum);
        energyLedger.merge(dim, (double) e1 - (double) e0, Double::sum);
        return true;
    }

    /** A fluid vanilla draws as water — the rule {@code RepresentativeState} draws waterlogging with. */
    private static boolean drawsAsWater(Material m) {
        return m.movable() && WATER_BLOCK.equals(m.representativeBlock());
    }

    /**
     * The cell's derived {@code T}: its material's curve for a one-component cell, the linear mixture
     * {@code (E − Σ m_j·h_j(0)) / Σ m_j·cp_j} otherwise (the {@link SurfaceForcing} rule).
     */
    private static double derivedT(SectionData data, int cell, Function<Identifier, Material> lookup) {
        double capacity = 0.0;
        double h0 = 0.0;
        int occupied = 0;
        Material only = null;
        for (int s = 0; s < SectionData.SLOTS; s++) {
            Material m = lookup.apply(slotMaterial(data, s, cell));
            float mass = slotMass(data, s, cell);
            if (m == null || mass <= 0f) {
                continue;
            }
            capacity += (double) mass * m.heatCapacity();
            h0 += (double) mass * EnthalpyCurve.hOf(m, lookup, 0f);
            occupied++;
            only = m;
        }
        return occupied == 1
                ? EnthalpyCurve.deriveT(data.enthalpyAt(cell), data.cellMassAt(cell), only, lookup,
                        SectionData.DEFAULT_AMBIENT_K)
                : (data.enthalpyAt(cell) - h0) / (capacity + 1e-12);
    }

    private static Identifier slotMaterial(SectionData data, int k, int cell) {
        return k == 0 ? data.materialAt(cell) : data.slotMaterialAt(k, cell);
    }

    private static float slotMass(SectionData data, int k, int cell) {
        return k == 0 ? data.massAt(cell) : data.slotMassAt(k, cell);
    }
}
