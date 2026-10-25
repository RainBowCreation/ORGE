package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.FluidReconcileDecider;
import net.rainbowcreation.orge.phase.FluidReconcileLogic;
import net.rainbowcreation.orge.phase.RepresentativeState;
import net.rainbowcreation.orge.section.MaterialPalette;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.rainbowcreation.orge.scheduler.SkyTestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HydrationDrain} (issue #35): a hydrating farmland's random tick draws {@code drainKg} of water from
 * the source cell vanilla found; an exhausted source empties (air, or its dry waterlogged host); the drained
 * mass and its enthalpy are a boundary-ledger sink, exact per species.
 */
class HydrationDrainTest {

    private static final Identifier OAK_STAIRS = orge("oak_stairs");
    private static final SubchunkKey SECTION = new SubchunkKey(0, 4, 0);
    /** World block (3, 72, 5): section 4, local y 8. */
    private static final int BX = 3, BY = 4 * 16 + 8, BZ = 5;
    private static final int CELL = cell(3, 8, 5);

    /** Records the sections the drain woke. */
    private static final class Wakes implements WakeSink {
        final List<SubchunkKey> flow = new ArrayList<>();
        @Override public void wakeBlock(Identifier dim, int x, int y, int z) { }
        @Override public void wakeFlowSection(Identifier dim, SubchunkKey key) { flow.add(key); }
        @Override public void wakeThermalSection(Identifier dim, SubchunkKey key) { }
    }

    private static SectionStore world(Path dir) {
        SectionStore store = loaded(dir, OVERWORLD).store(OVERWORLD);
        flatWorld(store, null, 285f);
        return store;
    }

    private static void apply(HydrationDrain drain, SectionStore store, WakeSink wake) {
        drain.apply(dim -> OVERWORLD.equals(dim) ? store : null, lookup(), wake);
    }

    /** A cell of {@code host} (at its default mass) holding {@code waterKg} of pore water, slot order as given. */
    private static void waterlogged(SectionData d, int i, boolean waterFirst, float waterKg, float tempK) {
        Material host = mat(OAK_STAIRS);
        Material water = mat(WATER);
        Identifier first = waterFirst ? WATER : OAK_STAIRS;
        Identifier second = waterFirst ? OAK_STAIRS : WATER;
        float m0 = waterFirst ? waterKg : host.defaultMass();
        float m1 = waterFirst ? host.defaultMass() : waterKg;
        d.setMaterialAt(i, first);
        d.setMass(i, m0);
        d.setSlot(1, i, second, m1);
        d.setEnthalpy(i, (float) (EnthalpyCurve.cellE(host.defaultMass(), host, lookup(), tempK)
                + EnthalpyCurve.cellE(waterKg, water, lookup(), tempK)));
    }

    /** The cell's occupied composition, as the reconciler reads it. */
    private static List<RepresentativeState.Component> components(SectionData d, int i) {
        List<RepresentativeState.Component> out = new ArrayList<>();
        if (d.massAt(i) > 0f) out.add(new RepresentativeState.Component(mat(d.materialAt(i)), d.massAt(i)));
        for (int k = 1; k < SectionData.SLOTS; k++) {
            if (d.slotMassAt(k, i) > 0f) {
                out.add(new RepresentativeState.Component(mat(d.slotMaterialAt(k, i)), d.slotMassAt(k, i)));
            }
        }
        return out;
    }

    /** Exact Σ mass per species over every stored section of column (0,0). */
    private static Map<Identifier, BigDecimal> speciesMass(SectionStore store) {
        Map<Identifier, BigDecimal> out = new HashMap<>();
        for (int sy = ColumnAssembler.MIN_SECTION_Y; sy <= ColumnAssembler.MAX_SECTION_Y; sy++) {
            SubchunkKey key = new SubchunkKey(0, sy, 0);
            if (!store.hasSection(key)) continue;
            SectionData d = store.get(key);
            for (int i = 0; i < SectionData.CELLS; i++) {
                out.merge(d.materialAt(i), new BigDecimal(d.massAt(i)), BigDecimal::add);
                for (int k = 1; k < SectionData.SLOTS; k++) {
                    out.merge(d.slotMaterialAt(k, i), new BigDecimal(d.slotMassAt(k, i)), BigDecimal::add);
                }
            }
        }
        out.values().removeIf(v -> v.signum() == 0);
        return out;
    }

    @Test
    void hydrationLowersTheSourceWaterByTheConfiguredAmount(@TempDir Path dir) {
        SectionStore store = world(dir);
        SectionData d = store.get(SECTION);
        setCell(d, CELL, mat(WATER), 1000f, 290f);
        HydrationDrain drain = new HydrationDrain(0.5f);
        Wakes wakes = new Wakes();

        drain.request(OVERWORLD, BX, BY, BZ);
        assertEquals(1, drain.pending());
        apply(drain, store, wakes);

        assertEquals(1000f - 0.5f, d.massAt(CELL), 0f, "the source lost exactly the configured mass");
        assertEquals(WATER, d.materialAt(CELL));
        assertEquals(290f, tempOf(d, CELL), 1e-3f, "the remaining water keeps its temperature");
        assertEquals(-0.5, drain.massLedger(OVERWORLD, WATER), 0.0, "the drained mass is a boundary sink");
        assertTrue(drain.energyLedger(OVERWORLD) < 0, "and carries its enthalpy out");
        assertEquals(0, drain.pending(), "a request is applied once");
        assertEquals(List.of(SECTION), wakes.flow, "the drained section steps and reconciles");

        apply(drain, store, wakes);
        assertEquals(1000f - 0.5f, d.massAt(CELL), 0f, "nothing queued, nothing drained");
    }

    @Test
    void anExhaustedFreeSourceBecomesAir(@TempDir Path dir) {
        SectionStore store = world(dir);
        SectionData d = store.get(SECTION);
        setCell(d, CELL, mat(WATER), 0.3f, 285f);
        HydrationDrain drain = new HydrationDrain(0.5f);

        drain.request(OVERWORLD, BX, BY, BZ);
        apply(drain, store, new Wakes());

        assertEquals(0f, d.cellMassAt(CELL), 0f, "drained to empty, never negative");
        assertEquals(0f, d.enthalpyAt(CELL), 0f, "an empty cell holds no energy");
        assertEquals(MaterialPalette.VACUUM_ID, d.materialAt(CELL), "the empty slot is (orge:vacuum, 0)");
        assertEquals(new BigDecimal(-0.3f).doubleValue(), drain.massLedger(OVERWORLD, WATER), 0.0,
                "only what the cell held is booked");
        // The reconciler sees a water block over an empty cell (the engine reports no species for it).
        FluidReconcileDecider.Action action = FluidReconcileDecider.decide(
                mat(WATER), mat(WATER), d.massAt(CELL),
                FluidReconcileLogic.levelBucket(0), true, false, false, false);
        assertEquals(FluidReconcileDecider.Kind.CLEAR, action.kind(), "the source block is cleared to air");
    }

    @Test
    void anExhaustedWaterloggedSourceKeepsItsDryHostInEitherSlotOrder(@TempDir Path dir) {
        for (boolean waterFirst : new boolean[] {true, false}) {
            SectionStore store = world(dir.resolve(waterFirst ? "w" : "h"));
            SectionData d = store.get(SECTION);
            Material host = mat(OAK_STAIRS);
            waterlogged(d, CELL, waterFirst, 0.8f, 285f);
            assertNotNull(RepresentativeState.waterlogged(host, components(d, CELL), true),
                    "precondition: the stair is a host with open volume");
            HydrationDrain drain = new HydrationDrain(0.5f);

            drain.request(OVERWORLD, BX, BY, BZ);
            drain.request(OVERWORLD, BX, BY, BZ);
            apply(drain, store, new Wakes());

            List<RepresentativeState.Component> comps = components(d, CELL);
            assertEquals(1, comps.size(), "only the host is left");
            assertEquals(OAK_STAIRS, comps.get(0).material().id());
            assertEquals(host.defaultMass(), comps.get(0).massKg(), 0f, "the host's own mass is never drained");
            assertTrue(RepresentativeState.hostPresent(host, comps), "the stair stays");
            assertEquals(Boolean.FALSE, RepresentativeState.waterlogged(host, comps, true), "and is drawn dry");
            assertEquals(new BigDecimal(-0.8f).doubleValue(), drain.massLedger(OVERWORLD, WATER), 0.0);
            assertEquals(0.0, drain.massLedger(OVERWORLD, OAK_STAIRS), 0.0);
            assertEquals(285f, hostTemp(d, host), 0.05f, "the dry host keeps its temperature");
        }
    }

    private static float hostTemp(SectionData d, Material host) {
        return EnthalpyCurve.deriveT(d.enthalpyAt(CELL), d.cellMassAt(CELL), host, lookup(), Float.NaN);
    }

    @Test
    void grandAndPerSpeciesMassAndEnergyAreExactIncludingTheLedger(@TempDir Path dir) {
        SectionStore store = world(dir);
        SectionData d = store.get(SECTION);
        // A canal of free water at mixed fills, two waterlogged stairs, and a dry stone cell.
        float[] fills = {1000f, 999.7f, 2.5f, 0.0021f, 640.25f};
        for (int x = 0; x < fills.length; x++) setCell(d, cell(x, 8, 0), mat(WATER), fills[x], 280f + x);
        waterlogged(d, cell(0, 8, 1), true, 312.5f, 291f);
        waterlogged(d, cell(1, 8, 1), false, 0.004f, 293f);
        Map<Identifier, BigDecimal> mass0 = speciesMass(store);
        BigDecimal e0 = grandE(store);
        HydrationDrain drain = new HydrationDrain(HydrationDrain.DEFAULT_DRAIN_KG);

        for (int round = 0; round < 700; round++) {
            for (int x = 0; x < fills.length; x++) drain.request(OVERWORLD, x, BY, 0);
            drain.request(OVERWORLD, 0, BY, 16 + 1);      // unloaded column: nothing to drain
            drain.request(OVERWORLD, 0, BY, 1);
            drain.request(OVERWORLD, 1, BY, 1);
            drain.request(OVERWORLD, 1, BY, 1);           // repeats in one window accumulate
            drain.request(OVERWORLD, 9, 4 * 16 + 3, 9);   // stone: no water component
            apply(drain, store, new Wakes());
        }

        Map<Identifier, BigDecimal> mass1 = speciesMass(store);
        assertTrue(mass1.get(WATER).compareTo(mass0.get(WATER)) < 0, "water was drunk");
        assertEquals(0f, d.cellMassAt(cell(3, 8, 0)), 0f, "a small source ran dry");
        for (Identifier species : mass0.keySet()) {
            BigDecimal ledger = new BigDecimal(drain.massLedger(OVERWORLD, species));
            BigDecimal now = mass1.getOrDefault(species, BigDecimal.ZERO);
            assertEquals(0, now.subtract(mass0.get(species)).compareTo(ledger),
                    species + ": Σ m change " + now.subtract(mass0.get(species)) + " must equal the ledger " + ledger);
        }
        BigDecimal grand0 = mass0.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grand1 = mass1.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, grand1.subtract(grand0).compareTo(new BigDecimal(drain.massLedger(OVERWORLD))),
                "grand mass + ledger is exact");
        assertEquals(0, grandE(store).subtract(e0).compareTo(new BigDecimal(drain.energyLedger(OVERWORLD))),
                "Σ E + energy ledger is exact");
        assertEquals(mass0.get(STONE), mass1.get(STONE), "nothing but water is drained");
    }

    @Test
    void aCellNotInTheSimulationIsNotDrained(@TempDir Path dir) {
        SectionStore store = world(dir);
        HydrationDrain drain = new HydrationDrain(0.5f);
        Wakes wakes = new Wakes();
        drain.request(OVERWORLD, BX, 7 * 16, BZ);               // section 7: never stored
        drain.request(OVERWORLD, BX, BY, BZ);                   // air: no water in the cell
        drain.request(SkyTestWorld.THE_END, BX, BY, BZ);        // a dimension with no store
        apply(drain, store, wakes);
        assertFalse(store.hasSection(new SubchunkKey(0, 7, 0)), "a read never stores a section");
        assertEquals(0.0, drain.massLedger(OVERWORLD), 0.0);
        assertEquals(0.0, drain.energyLedger(OVERWORLD), 0.0);
        assertTrue(wakes.flow.isEmpty(), "nothing drained, nothing woken");
        assertEquals(0, drain.pending());
    }

    @Test
    void theDrainAmountIsConfiguredByAProperty() {
        assertEquals(0.25f, HydrationDrain.drainKgFrom("0.25"), 0f);
        assertEquals(HydrationDrain.DEFAULT_DRAIN_KG, HydrationDrain.drainKgFrom(null), 0f);
        assertEquals(HydrationDrain.DEFAULT_DRAIN_KG, HydrationDrain.drainKgFrom("lots"), 0f);
        assertEquals(HydrationDrain.DEFAULT_DRAIN_KG, HydrationDrain.drainKgFrom("-1"), 0f);
        assertEquals(0f, HydrationDrain.drainKgFrom("0"), 0f, "0 turns thirsty farmland off");
        // FAO-56 ET0 5 mm/day over 1 m² for the mean random-tick interval at randomTickSpeed 3.
        assertEquals(5.0 / 86400.0 * (4096.0 / 3.0 / 20.0), HydrationDrain.DEFAULT_DRAIN_KG, 1e-9);
    }
}
