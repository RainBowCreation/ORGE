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
    void massBeforeTempEncodesTemperatureAtTheNewMass(@TempDir Path dir) {
        // Reproduces the /orge set bug end-to-end through the real sink: a low-mass cell set to a
        // higher mass + temperature. writeTemp encodes E = mass·h(T) from the cell's current mass,
        // so mass must be written FIRST. Old order (temp then mass) pinned E to 1.2 kg and the read
        // derived 300·1.2/3000 = 0.12 K. This asserts the fixed mass-first order round-trips.
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        SectionStore store = mgr.store(DIM);
        SectionData d = store.get(key);
        d.setMass(9, 1.2f);              // cell starts light
        d.setMaterialAt(9, WATER);
        store.put(key, d);

        sink.writeMass(DIM, key, 9, 3000f); // mass first (the fixed /orge set order)
        sink.writeTemp(DIM, key, 9, 300f);

        SectionView v = src.section(DIM, key).orElseThrow();
        assertEquals(3000f, v.massAt(9), 0.001f);
        assertEquals(300f, v.tempAt(9), 0.1f,
                "T must encode at the new mass, not 300*1.2/3000 = 0.12 K");
    }

    /** Give a cell a resolvable species + mass with a specific stored E (J) directly. */
    private void seedCellWithE(SectionStoreManager mgr, SubchunkKey key, int cell,
                              Identifier matId, float massKg, float enthalpyJ) {
        SectionStore store = mgr.store(DIM);
        SectionData data = store.get(key);
        data.setMass(cell, massKg);
        data.setMaterialAt(cell, matId);
        data.setEnthalpy(cell, enthalpyJ);
        store.put(key, data);
    }

    /** Law §7 display-derive round-trip: {@code /orge set} 350 K then {@code /orge get} reads ~350 K,
     *  proving T is encoded to E on write and re-derived from E on read (never stored). */
    @Test
    void setTempThenGetDerivesSameKelvin(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        seedWaterCell(mgr, key, 11); // resolvable orge:water + 1000 kg
        sink.writeTemp(DIM, key, 11, 350f);

        SectionView v = src.section(DIM, key).orElseThrow();
        assertEquals(350f, v.tempAt(11), 0.05f,
                "off-plateau round-trip: write encodes kelvin->E, read derives E->kelvin");
    }

    /** A cell whose stored E sits mid-latent-plateau shows the PINNED plateau T (~373 K), not a runaway
     *  E/(m·cp) value — proving the display derive uses the enthalpy curve's plateau. The [0,6000] clamp
     *  is not even exercised here (373 is in range). */
    @Test
    void getOnMidPlateauCellShowsPinnedT(@TempDir Path dir) {
        // Use a latent-bearing water so the boil plateau exists (TestMaterials.water() has no latent heat).
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(TestMaterials.waterWithLatent());
        reg.put(TestMaterials.steam());
        ActiveMaterials.swap(new ActiveMaterials.State(reg));

        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        float massKg = 1000f;
        var matLookup = (java.util.function.Function<Identifier, net.rainbowcreation.orge.material.Material>)
                id -> reg.get(id).orElse(null);
        net.rainbowcreation.orge.material.Material water = matLookup.apply(WATER);
        // Mid boil-plateau: E = m·(h(373.15) + L/2), pinned at 373.15 K.
        double hStar = net.rainbowcreation.orge.material.EnthalpyCurve.hOf(water, matLookup, 373.15f);
        double midEta = hStar + water.latentHeatMax() / 2.0;
        float midE = (float) (massKg * midEta);
        seedCellWithE(mgr, key, 13, WATER, massKg, midE);

        SectionView v = src.section(DIM, key).orElseThrow();
        assertEquals(373.15f, v.tempAt(13), 0.5f,
                "mid-plateau E shows pinned plateau T, not E/(m·cp) runaway");
    }

    /** An absurd stored E shows the CLAMPED [0,6000] display boundary, not a nonsense 1e8 K — the display
     *  clamp defends the UI without ever touching the stored E. */
    @Test
    void getOnCorruptHugeEClampsDisplayT(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        // 1e12 J on 1 kg of water -> raw derive ~1e8/4186 K; clamp pins it to 6000.
        seedCellWithE(mgr, key, 17, WATER, 1f, 1e12f);

        SectionView v = src.section(DIM, key).orElseThrow();
        assertEquals(6000f, v.tempAt(17), 0.001f,
                "corrupt huge E shows the clamped 6000 K display boundary");
    }

    /** A void / massless / unresolved-species cell shows the ambient fallback (no NaN/inf). */
    @Test
    void getOnMasslessOrUnresolvedShowsAmbient(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        // Massless water cell: no enthalpy -> ambient fallback.
        seedCellWithE(mgr, key, 19, WATER, 0f, 0f);
        // Unresolved species (never installed in the table) -> ambient fallback.
        Identifier unknown = Identifier.fromNamespaceAndPath("orge", "unobtainium");
        seedCellWithE(mgr, key, 21, unknown, 1000f, 1e6f);

        SectionView v = src.section(DIM, key).orElseThrow();
        float massless = v.tempAt(19);
        float unresolved = v.tempAt(21);
        assertEquals(SectionData.DEFAULT_AMBIENT_K, massless, 0.001f, "massless cell -> ambient");
        assertEquals(SectionData.DEFAULT_AMBIENT_K, unresolved, 0.001f, "unresolved species -> ambient");
        assertTrue(Float.isFinite(massless) && Float.isFinite(unresolved), "no NaN/inf");
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
