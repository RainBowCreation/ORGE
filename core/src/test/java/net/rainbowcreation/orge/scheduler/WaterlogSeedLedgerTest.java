package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.LegacyColumns;
import net.rainbowcreation.orge.section.RegionStore;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.rainbowcreation.orge.scheduler.SkyTestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #60 (proposal §12 Q4 (B), PRD #24 hard constraint 3): the water a waterlogged block of a legacy
 * (pre-composition, v5) world is given on its first simulation is a LEDGERED boundary placement — grand
 * mass, water mass and grand E close exactly against the {@link WaterlogSeeding} ledgers, and the seed
 * lands, and is booked, once.
 */
class WaterlogSeedLedgerTest {

    private static final Identifier STAIRS = orge("stone_stairs");
    private static final Identifier SLAB = orge("stone_slab");
    /** A waterloggable host that is nearly all open volume: 1 kg in 1 m³ at ρ_p 1000. */
    private static final Identifier LICHEN = orge("glow_lichen");
    private static final float TEMP_K = 288f;
    private static final int SURFACE_Y = 4;
    private static final int SY = 8;

    private static final MaterialLut LUT = TestMaterials.lutOf(List.of(
            mat(AIR), mat(STONE), mat(WATER), mat(STAIRS), mat(SLAB), mat(LICHEN)));

    /** The waterlogged hosts of the surface row: x → host. */
    private static final Map<Integer, Identifier> HOSTS = Map.of(0, STAIRS, 1, SLAB, 2, LICHEN);

    /** Live blocks: section 3 stone; section 4 stone below y 8, the hosts (else air) at y 8, air above. */
    private static Identifier live(int sectionY, int i) {
        int x = i & 15, sy = (i >> 4) & 15, z = i >> 8;
        if (sectionY == 3) return STONE;
        if (sy < SY) return STONE;
        if (sy == SY && z == 0 && HOSTS.containsKey(x)) return HOSTS.get(x);
        return AIR;
    }

    /** The old world as an old build stored it: sections 3–4, one component per cell, E on each curve. */
    private static Map<Integer, SectionData> oldWorld() {
        Map<Integer, SectionData> column = new HashMap<>();
        for (int sectionY = 3; sectionY <= SURFACE_Y; sectionY++) {
            SectionData d = SectionData.uniform(0f, 0f);
            for (int i = 0; i < SectionData.CELLS; i++) {
                Material m = mat(live(sectionY, i));
                setCell(d, i, m, m.defaultMass(), TEMP_K);
            }
            column.put(sectionY, d);
        }
        return column;
    }

    /** The snapshot's read + assemble of column (0,0): the stored sections, and nothing (void) elsewhere. */
    private static ThermalWorld.ColumnEntry firstStep(SectionStore store) {
        WaterlogSeeding.Seeds seeds = new WaterlogSeeding.Seeds();
        Material water = mat(WATER);
        ColumnTask task = ColumnAssembler.assemble(0, 0, LUT, registry(), (cx, cz, sectionY) -> {
            if (sectionY < 3 || sectionY > SURFACE_Y) {
                return new ColumnAssembler.SectionCells(new char[SectionData.CELLS],
                        new float[SectionData.CELLS], new float[SectionData.CELLS]);
            }
            GeometryAssembler.CellMaterials cellMat = i -> mat(live(sectionY, i));
            float[] waterlogSeed = new float[SectionData.CELLS];
            for (int i = 0; i < SectionData.CELLS; i++) {
                if (HOSTS.containsValue(live(sectionY, i))) {
                    waterlogSeed[i] = ColumnAssembler.waterlogSeedKg(cellMat.at(i), water);
                }
            }
            return SectionCellsReader.read(store, new SubchunkKey(cx, sectionY, cz),
                    GeometryAssembler.assemble(cellMat, LUT), cellMat, lookup(),
                    new char[SectionData.CELLS], waterlogSeed, TEMP_K);
        }, seeds);
        return new ThermalWorld.ColumnEntry(OVERWORLD, 0, 0, task, seeds);
    }

    /** An engine that changes nothing: every channel, slots included, comes back as it went in. */
    private static ColumnResult echo(ColumnTask t) {
        return new ColumnResult(t.matIx().clone(), t.mass().clone(), t.temperature().clone(),
                t.momX().clone(), t.momY().clone(), t.momZ().clone(), t.p().clone(), t.swapReady().clone(),
                t.enthalpy().clone(), t.slotIx().clone(), t.slotMass().clone());
    }

    /** Exact Σ mass per species over {@code sections}, every slot of every cell. */
    private static Map<Identifier, BigDecimal> speciesMass(Collection<SectionData> sections) {
        Map<Identifier, BigDecimal> out = new HashMap<>();
        for (SectionData d : sections) {
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

    private static BigDecimal energy(Collection<SectionData> sections) {
        BigDecimal sum = BigDecimal.ZERO;
        for (SectionData d : sections) {
            for (int i = 0; i < SectionData.CELLS; i++) sum = sum.add(new BigDecimal(d.enthalpyAt(i)));
        }
        return sum;
    }

    private static List<SectionData> stored(SectionStore store) {
        List<SectionData> out = new java.util.ArrayList<>();
        for (int sy = ColumnAssembler.MIN_SECTION_Y; sy <= ColumnAssembler.MAX_SECTION_Y; sy++) {
            SubchunkKey key = new SubchunkKey(0, sy, 0);
            if (store.hasSection(key)) out.add(store.get(key));
        }
        return out;
    }

    private static BigDecimal sum(Map<Identifier, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Σ m − ledger = pre-load, per species and grand; Σ E − ledger = pre-load. */
    private static void assertBooksClose(SectionStore store, WaterlogSeeding ledger,
                                         Map<Identifier, BigDecimal> mass0, BigDecimal e0, String when) {
        Map<Identifier, BigDecimal> mass1 = speciesMass(stored(store));
        BigDecimal waterLedger = new BigDecimal(ledger.mass(OVERWORLD));
        for (Identifier species : mass1.keySet()) {
            BigDecimal booked = species.equals(WaterlogSeeding.WATER) ? waterLedger : BigDecimal.ZERO;
            BigDecimal before = mass0.getOrDefault(species, BigDecimal.ZERO);
            assertEquals(0, mass1.get(species).subtract(booked).compareTo(before),
                    when + ": " + species + " Σ m " + mass1.get(species) + " − ledger " + booked
                            + " must equal the pre-load " + before);
        }
        assertEquals(mass0.keySet(), mass1.keySet().stream().filter(s -> !s.equals(WaterlogSeeding.WATER))
                .collect(java.util.stream.Collectors.toSet()), when + ": no species lost or gained but water");
        assertEquals(0, sum(mass1).subtract(waterLedger).compareTo(sum(mass0)),
                when + ": grand Σ m − ledger = pre-load total");
        BigDecimal eLedger = new BigDecimal(ledger.energy(OVERWORLD));
        assertEquals(0, energy(stored(store)).subtract(eLedger).compareTo(e0),
                when + ": grand Σ E " + energy(stored(store)) + " − ledger " + eLedger + " = pre-load " + e0);
    }

    @Test
    void aLegacyWaterloggedSeedIsABoundaryPlacementBookedOnce(@TempDir Path dir) throws IOException {
        Map<Integer, SectionData> old = oldWorld();
        Map<Identifier, BigDecimal> mass0 = speciesMass(old.values());
        BigDecimal e0 = energy(old.values());
        assertNull(mass0.get(WATER), "the old world's waterlogged blocks hold no water");
        LegacyColumns.writeV5(dir, 0, 0, old);

        SectionStoreManager mgr = loaded(dir, OVERWORLD);
        RegionStore.MigrationAudit audit = mgr.migrationAudit(OVERWORLD);
        assertEquals(1, audit.columns(), "the v5 column migrated on load");
        assertTrue(audit.identical(), "load itself is a no-op on the books");
        SectionStore store = mgr.store(OVERWORLD);
        assertTrue(store.get(new SubchunkKey(0, SURFACE_Y, 0)).isLegacyOneComponent());
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        world.setLastColumnLutForTest(LUT.materials());
        WaterlogSeeding ledger = world.waterlogSeeding();
        assertBooksClose(store, ledger, mass0, e0, "after load");

        // A held step writes nothing back: it books nothing, and the next snapshot seeds the water again.
        firstStep(store);
        assertEquals(0.0, ledger.mass(OVERWORLD), 0.0, "a step that never lands books nothing");

        ThermalWorld.ColumnEntry first = firstStep(store);
        double want = 0.0;
        for (int x = 0; x <= 2; x++) {
            int ci = RegionMarshaller.colIdx(x, SURFACE_Y * 16 + SY + 64, 0);
            float wl = ColumnAssembler.waterlogSeedKg(mat(HOSTS.get(x)), mat(WATER));
            assertTrue(wl > 0f, HOSTS.get(x) + " has open volume");
            assertEquals(wl, first.task().slotMass()[ci], 0f, HOSTS.get(x) + " carries its water in slot 1");
            want += wl;
        }
        assertEquals(want, first.waterlog().kg(SURFACE_Y), 0.0, "the seed is recorded with its section");
        world.writeBackColumn(first, echo(first.task()));

        assertEquals(want, ledger.mass(OVERWORLD), 0.0, "the seeded water is booked");
        assertTrue(ledger.energy(OVERWORLD) > 0.0, "with the enthalpy it brought");
        assertFalse(store.get(new SubchunkKey(0, SURFACE_Y, 0)).isLegacyOneComponent(),
                "the persisted section has had its one seed");
        assertBooksClose(store, ledger, mass0, e0, "after the first step");

        // A second assembly of the same sections seeds nothing and books nothing.
        double kgBooked = ledger.mass(OVERWORLD);
        double eBooked = ledger.energy(OVERWORLD);
        ThermalWorld.ColumnEntry second = firstStep(store);
        for (int sy = ColumnAssembler.MIN_SECTION_Y; sy <= ColumnAssembler.MAX_SECTION_Y; sy++) {
            assertEquals(0.0, second.waterlog().kg(sy), 0.0, "section " + sy + " seeds nothing again");
        }
        world.writeBackColumn(second, echo(second.task()));
        assertEquals(kgBooked, ledger.mass(OVERWORLD), 0.0, "nothing more is booked");
        assertEquals(eBooked, ledger.energy(OVERWORLD), 0.0);
        assertBooksClose(store, ledger, mass0, e0, "after the second step");
    }
}
