package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #31 (PRD #24 constraint 4): a pre-change (v5, one-material) world loads on the multi-material
 * build, and the grand mass + energy totals are LOGGED before and after the migration so a real server
 * run proves them identical. "Before" is what the v5 bytes on disk say; "after" is the migrated cells,
 * every slot of every cell.
 */
class MigrationAuditTest {

    @TempDir
    Path world;

    private static final Identifier STONE = Identifier.fromNamespaceAndPath("orge", "stone");
    private static final Identifier SAND = Identifier.fromNamespaceAndPath("orge", "sand");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");

    private static byte[] v5FixtureBlob() throws IOException {
        try (InputStream in = MigrationAuditTest.class.getResourceAsStream("/fixtures/orge-column-v5.bin")) {
            assertNotNull(in, "fixture fixtures/orge-column-v5.bin must be on the test classpath");
            return in.readAllBytes();
        }
    }

    /** Puts a raw column blob into the region file for chunk (cx, cz), exactly as an old build left it. */
    private void writeRawColumn(int cx, int cz, byte[] blob) throws IOException {
        Path dir = world.resolve(RegionStore.DIR_NAME);
        java.nio.file.Files.createDirectories(dir);
        try (RegionFile rf = new RegionFile(dir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".orge"))) {
            rf.writeChunk(cx, cz, blob);
        }
    }

    /** The fixture's totals, computed from how {@code SectionCodecTest} built it (independent of the codec):
     *  a FULL section of stone / sand / water / air rows plus a UNIFORM air section. */
    private static Map<Identifier, Double> fixtureSpecies() {
        Map<Identifier, Double> kg = new TreeMap<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            int sy = (i >> 4) & 15;
            Identifier id; float m;
            if (sy < 4)       { id = STONE; m = 2700f; }
            else if (sy < 6)  { id = SAND;  m = 1600f; }
            else if (sy < 11) { id = WATER; m = 1000f - (i % 7) * 0.125f; }
            else              { id = AIR;   m = 1.204f; }
            kg.merge(id, (double) m, Double::sum);
        }
        return kg;
    }

    @Test
    void migratingAV5ColumnRecordsIdenticalGrandMassAndEnergyBeforeAndAfter() throws IOException {
        writeRawColumn(3, -2, v5FixtureBlob());
        try (RegionStore store = new RegionStore(world)) {
            assertEquals(0, store.migrationAudit().columns(), "nothing migrated before a load");

            store.loadColumn(3, -2);

            RegionStore.MigrationAudit audit = store.migrationAudit();
            assertEquals(1, audit.columns(), "one v5 column migrated");
            ColumnTotals before = audit.before();
            ColumnTotals after = audit.after();
            assertTrue(before.massKg() > 0 && before.energyJ() > 0, "the old world is non-trivial");
            assertEquals(before.massKg(), after.massKg(), "grand mass identical");
            assertEquals(before.energyJ(), after.energyJ(), "grand E identical");
            assertEquals(before.speciesKg(), after.speciesKg(), "per-species mass identical");
            assertTrue(audit.identical());

            // "before" is what the old bytes hold: the per-species masses the fixture was built with
            // (the FULL section) plus the UNIFORM air section (4096 cells of 1.204 kg).
            Map<Identifier, Double> want = fixtureSpecies();
            for (Identifier id : want.keySet()) {
                if (!id.equals(AIR)) {
                    assertEquals(want.get(id), before.speciesKg().get(id), 1e-6, id + " kg on disk");
                }
            }
            double uniformAir = 0;
            for (int i = 0; i < SectionData.CELLS; i++) uniformAir += 1.204f;
            assertEquals(want.get(AIR), before.speciesKg().get(AIR), 1e-6, "air rows of the FULL section");
            assertEquals(uniformAir, before.speciesKg().get(MaterialPalette.VACUUM_ID), 1e-6,
                    "the UNIFORM section carries no material layer: its mass is booked to orge:vacuum, unchanged");
        }
    }

    @Test
    void aV6ColumnIsNotAMigration() throws IOException {
        byte[] v6 = SectionCodec.writeColumn(SectionCodec.readColumn(v5FixtureBlob()));
        assertEquals(SectionCodec.FORMAT_VERSION, v6[0]);
        writeRawColumn(0, 0, v6);
        try (RegionStore store = new RegionStore(world)) {
            assertFalse(store.loadColumn(0, 0).isEmpty(), "the column loads");
            assertEquals(0, store.migrationAudit().columns(), "a v6 column is not migrated");
        }
    }

    @Test
    void auditAccumulatesAcrossColumnsAndReportsItsSummary() throws IOException {
        byte[] blob = v5FixtureBlob();
        writeRawColumn(0, 0, blob);
        writeRawColumn(40, 7, blob);   // a second region file
        try (RegionStore store = new RegionStore(world)) {
            store.loadColumn(0, 0);
            ColumnTotals one = store.migrationAudit().before();
            store.loadColumn(40, 7);
            RegionStore.MigrationAudit audit = store.migrationAudit();
            assertEquals(2, audit.columns());
            assertEquals(2 * one.massKg(), audit.before().massKg(), 1e-6 * one.massKg());
            assertEquals(audit.before(), audit.after());
            String s = audit.summary();
            assertTrue(s.contains("2 columns"), s);
            assertTrue(s.contains("IDENTICAL"), s);
            assertTrue(s.contains("orge:water"), s);
        }
    }

    @Test
    void aLoadedDimensionExposesItsMigrationBooks() throws IOException {
        Identifier overworld = Identifier.fromNamespaceAndPath("minecraft", "overworld");
        writeRawColumn(1, 1, v5FixtureBlob());
        SectionStoreManager mgr = new SectionStoreManager();
        assertNull(mgr.migrationAudit(overworld), "unknown dimension");
        mgr.onLevelLoad(overworld, world, key -> 288f);
        try {
            mgr.onChunkLoad(overworld, 1, 1);
            RegionStore.MigrationAudit audit = mgr.migrationAudit(overworld);
            assertEquals(1, audit.columns());
            assertTrue(audit.identical());
            mgr.onLevelSave(overworld);   // logs the books once
            mgr.onLevelSave(overworld);   // nothing new migrated: no second line
            assertEquals(1, mgr.migrationAudit(overworld).columns());
        } finally {
            mgr.onLevelUnload(overworld);
        }
    }

    @Test
    void totalsThatDifferAreReportedAsAMismatch() {
        ColumnTotals a = new ColumnTotals();
        ColumnTotals b = new ColumnTotals();
        SectionData s = SectionData.uniform(1000.0, 1.0f);
        a.add(s);
        s.setMass(5, 2.0f);
        b.add(s);
        assertNotEquals(a, b);
        RegionStore.MigrationAudit audit = new RegionStore.MigrationAudit(1, a, b);
        assertFalse(audit.identical());
        assertTrue(audit.summary().contains("MISMATCH"), audit.summary());
    }
}
