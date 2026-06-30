package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: {@code /orge set} on a NEVER-SIMULATED cell must establish the cell's live species so the
 * temperature encode (E = m·h(T)) lands on the right enthalpy curve. Before the fix the write hit a
 * synthesized {@code orge:vacuum} stub, encoded 300 K against an enthalpy-less species → stored 0 J, and
 * the read came back as the ambient fallback (and a later snapshot re-seeded the cell to its block's
 * {@code default_temperature}). In-game symptom: {@code /orge set ~ 300 3000} on bedrock read back 290 K.
 *
 * <p>The command itself passes NO species — exactly like the real command. The {@link CellSpeciesSource}
 * (Orge.java reads the world block; here it is stubbed) supplies the live identity.
 */
class BedrockSetReproTest {

    static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    static final Identifier BEDROCK = Identifier.fromNamespaceAndPath("orge", "bedrock");

    /** bedrock.json mirror: cp 800, conductivity 0, min=max=default mass 3000, default_temperature 290. */
    static Material bedrock() {
        return Material.builder(BEDROCK)
                .thermalConductivity(0f).heatCapacity(800f).molarMass(0.065f)
                .defaultMass(3000f).defaultTemperature(290f)
                .viscosity(Float.POSITIVE_INFINITY).minMass(3000f).maxMass(3000f)
                .build();
    }

    @BeforeEach
    void installTable() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(bedrock());
        ActiveMaterials.swap(new ActiveMaterials.State(reg));
    }

    @AfterEach
    void resetTable() {
        ActiveMaterials.swap(new ActiveMaterials.State(new MaterialRegistry()));
    }

    private SectionStoreManager mgr(java.nio.file.Path dir) {
        SectionStoreManager m = new SectionStoreManager();
        m.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        m.onChunkLoad(DIM, 0, 0);
        return m;
    }

    @Test
    void orgeSetOnNeverSimulatedBedrockRoundTrips(@TempDir java.nio.file.Path dir) {
        SectionStoreManager mgr = mgr(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        // Live block at the target cell is bedrock — the resolver the real wiring injects (Orge.java reads
        // the world block; here we stub it). The command itself still passes NO species.
        CellSpeciesSource live = (dim, k, cell) -> BEDROCK;
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr, null, live);
        SubchunkKey key = new SubchunkKey(0, -4, 0); // y=-64 bedrock section

        // EXACTLY what OrgeCommandLogic.set does: mass first, then temp. NO material pre-seed.
        sink.writeMass(DIM, key, 5, 3000f);
        sink.writeTemp(DIM, key, 5, 300f);

        SectionStore store = mgr.store(DIM);
        SectionData d = store.get(key);
        assertEquals(BEDROCK, d.materialAt(5), "set must establish the cell's live species");
        assertTrue(d.enthalpyAt(5) > 0f, "encode must store real enthalpy, not the vacuum 0 J");

        SectionView v = src.section(DIM, key).orElseThrow();
        assertEquals(300f, v.tempAt(5), 0.5f, "/orge set 300 K on a bedrock cell must read back 300 K");
        assertEquals(3000f, v.massAt(5), 0.001f);
    }

    @Test
    void writeOnAlreadyEstablishedCellKeepsItsSpecies(@TempDir java.nio.file.Path dir) {
        // A cell with a real durable species (simulated/placed) must NOT be re-stomped to the block's
        // first-touch by a /orge set — the resolver only fills in an unestablished (vacuum) cell.
        Identifier water = Identifier.fromNamespaceAndPath("orge", "water");
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(bedrock());
        reg.put(net.rainbowcreation.orge.engine.TestMaterials.water());
        ActiveMaterials.swap(new ActiveMaterials.State(reg));

        SectionStoreManager mgr = mgr(dir);
        SubchunkKey key = new SubchunkKey(0, -4, 0);
        SectionStore store = mgr.store(DIM);
        SectionData seed = store.get(key);
        seed.setMass(5, 1000f);
        seed.setMaterialAt(5, water); // already-established water cell
        store.put(key, seed);

        // Resolver would say "bedrock" (the block), but the cell already has water identity.
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr, null, (dim, k, cell) -> BEDROCK);
        sink.writeTemp(DIM, key, 5, 350f);

        assertEquals(water, store.get(key).materialAt(5), "established species must not be clobbered");
    }
}
