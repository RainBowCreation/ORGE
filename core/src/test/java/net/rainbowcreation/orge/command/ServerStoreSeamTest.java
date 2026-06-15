package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read/write seam over the §5 store. Under law §7 the store holds extensive E, so a {@code /orge set}
 * temperature is encoded {@code E = m·h(T)} on write and {@code T = h⁻¹(E/m)} derived on read — which
 * only round-trips for a cell that carries a resolvable species AND mass (the enthalpy curve needs
 * both). The seam therefore installs an {@code orge:water} table and gives each edited cell water +
 * mass before writing a temperature. A bare (material-less / mass-less) cell has no enthalpy and reads
 * the ambient fallback. (S7 finalizes the display-derive wiring.)
 */
class ServerStoreSeamTest {

    static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");

    @BeforeEach
    void installWaterTable() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(TestMaterials.water());
        ActiveMaterials.swap(new ActiveMaterials.State(reg));
    }

    @AfterEach
    void resetTable() {
        ActiveMaterials.swap(new ActiveMaterials.State(new MaterialRegistry()));
    }

    private SectionStoreManager managerWithLoadedColumn(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0); // load column (0,0)
        return mgr;
    }

    /** Give a cell a resolvable species + mass so the kelvin↔E derive round-trips (law §7). */
    private void seedWaterCell(SectionStoreManager mgr, SubchunkKey key, int cell) {
        SectionStore store = mgr.store(DIM);
        SectionData data = store.get(key);
        data.setMass(cell, 1000f);
        data.setMaterialAt(cell, WATER);
        store.put(key, data);
    }

    @Test
    void readSourceReturnsAmbientBeforeWriteThenStoredAfter(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        SectionView before = src.section(DIM, key).orElseThrow();
        assertTrue(before.ambient(), "never-written section is ambient");
        assertEquals(285.0f, before.tempAt(0), 0.001f);

        seedWaterCell(mgr, key, 5);
        sink.writeTemp(DIM, key, 5, 400f);

        SectionView after = src.section(DIM, key).orElseThrow();
        assertFalse(after.ambient(), "after write it is stored");
        assertEquals(400f, after.tempAt(5), 0.05f, "kelvin round-trips through E = m·h(T) for a water cell");
    }

    @Test
    void writeTempThenMassDoNotClobber(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        seedWaterCell(mgr, key, 7);
        sink.writeTemp(DIM, key, 7, 350f);
        sink.writeMass(DIM, key, 7, 1000f);

        SectionView v = src.section(DIM, key).orElseThrow();
        assertEquals(350f, v.tempAt(7), 0.05f, "temperature (derived from stored E) preserved across the mass write");
        assertEquals(1000f, v.massAt(7), 0.001f);
    }

    @Test
    void isLoadedReflectsColumnState(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        assertTrue(sink.isLoaded(DIM, new SubchunkKey(0, 4, 0)));
        assertFalse(sink.isLoaded(DIM, new SubchunkKey(9, 4, 9)));
    }

    @Test
    void unknownDimensionYieldsEmptyRead(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        Optional<SectionView> v = src.section(
                Identifier.fromNamespaceAndPath("minecraft", "the_end"),
                new SubchunkKey(0, 4, 0));
        assertTrue(v.isEmpty(), "no store for dimension -> empty (let logic report it)");
    }
}
