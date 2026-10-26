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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link ServerCellStore} read view and write over a MULTI-COMPONENT cell (law v5 #7, issue #50):
 * {@code /orge get} reads the cell's total mass {@code Σ m_k}, its composition, and the T derived from its
 * one E on the mixture curve — not slot 0 alone, which comes back in LUT order and is often a trace.
 */
class ServerCellStoreTest {

    static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");
    static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    static final SubchunkKey KEY = new SubchunkKey(0, 4, 0);

    @BeforeEach
    void installTable() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(TestMaterials.air());
        reg.put(TestMaterials.stone());
        reg.put(TestMaterials.water());
        ActiveMaterials.swap(new ActiveMaterials.State(reg));
    }

    @AfterEach
    void resetTable() {
        ActiveMaterials.swap(new ActiveMaterials.State(new MaterialRegistry()));
    }

    private static SectionStoreManager manager(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0);
        return mgr;
    }

    /** A drained waterlogged stair: slot 0 a trace of air, slot 1 the solid, E = Σ m_k·h_k(273). */
    private static void seedDrainedStair(SectionStoreManager mgr, int cell) {
        SectionStore store = mgr.store(DIM);
        SectionData d = store.get(KEY);
        d.setMaterialAt(cell, AIR);
        d.setMass(cell, 0.0007f);
        d.setSlot(1, cell, STONE, 562.5f);
        d.setEnthalpy(cell, (0.0007 * 1005.0 + 562.5 * 840.0) * 273.0);
        store.put(KEY, d);
    }

    @Test
    void readShowsTotalMassCompositionAndMixtureT(@TempDir Path dir) {
        SectionStoreManager mgr = manager(dir);
        seedDrainedStair(mgr, 7);
        SectionView v = new ServerCellStore(mgr).read(DIM, KEY).orElseThrow();
        assertEquals(273f, v.tempAt(7), 0.01f, "mixture T, not slot 0's 6000 K clamp");
        assertEquals(562.5007f, v.massAt(7), 1e-4f, "total mass Σ m_k, not slot 0's 0.0007 kg");
        assertEquals(List.of(new SectionView.Component(AIR, 0.0007f), new SectionView.Component(STONE, 562.5f)),
                v.composition(7));
    }

    @Test
    void oneComponentCellCompositionIsItsSlotZero(@TempDir Path dir) {
        SectionStoreManager mgr = manager(dir);
        SectionStore store = mgr.store(DIM);
        SectionData d = store.get(KEY);
        d.setMaterialAt(3, WATER);
        d.setMass(3, 1000f);
        store.put(KEY, d);
        SectionView v = new ServerCellStore(mgr).read(DIM, KEY).orElseThrow();
        assertEquals(List.of(new SectionView.Component(WATER, 1000f)), v.composition(3));
        assertEquals(1, v.composition(5).size(), "an empty cell still reports its slot 0");
    }

    @Test
    void writeEncodesTheTemperatureOverTheWholeComposition(@TempDir Path dir) {
        SectionStoreManager mgr = manager(dir);
        seedDrainedStair(mgr, 7);
        ServerCellStore store = new ServerCellStore(mgr);
        store.write(DIM, KEY, 7, 300f, null);
        assertEquals((0.0007 * 1005.0 + 562.5 * 840.0) * 300.0, mgr.store(DIM).get(KEY).enthalpyAt(7), 1.0,
                "E = Σ m_k·h_k(300)");
        assertEquals(300f, store.read(DIM, KEY).orElseThrow().tempAt(7), 0.01f, "set 300 K reads back 300 K");
    }
}
