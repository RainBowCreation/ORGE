package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the stale-write-back clobber (in-game: {@code /orge set} on bedrock reverted to 290
 * after one tick). The async scheduler snapshots a section, simulates off-thread, and writes the result
 * back a cycle later. A {@code /orge set} into that window must NOT be overwritten by the stale result.
 * {@link SectionData#editedSinceSnapshot()} gates the write-back; this drives the real
 * {@link ColumnWriteBack} through the exact sequence.
 */
class WriteBackEditGuardTest {

    static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    static final Identifier BEDROCK = Identifier.fromNamespaceAndPath("orge", "bedrock");
    static final char VOID = 0, AIRX = 1, BEDX = 2;

    static Material voidMat() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "vacuum"))
                .thermalConductivity(0f).heatCapacity(1f).molarMass(0f)
                .defaultMass(0f).defaultTemperature(Float.NaN).viscosity(0f).minMass(0f).maxMass(0f).build();
    }
    static Material air() {
        return Material.builder(AIR).thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.002f)
                .defaultMass(1.2f).defaultTemperature(Float.NaN).viscosity(0f).minMass(1.0f).maxMass(1000f).build();
    }
    static Material bedrock() {
        return Material.builder(BEDROCK).thermalConductivity(0f).heatCapacity(800f).molarMass(0.065f)
                .defaultMass(3000f).defaultTemperature(290f)
                .viscosity(Float.POSITIVE_INFINITY).minMass(3000f).maxMass(3000f).build();
    }
    static final List<Material> LUT = List.of(voidMat(), air(), bedrock());

    private static final float E_290 = 6.96e8f; // 3000 * 800 * 290
    private static final float E_300 = 7.2e8f;  // 3000 * 800 * 300
    private static final SubchunkKey KEY = new SubchunkKey(0, -4, 0);
    private static final int CELL = 0;

    @BeforeEach
    void install() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(air());
        reg.put(bedrock());
        ActiveMaterials.swap(new ActiveMaterials.State(reg));
    }

    @AfterEach
    void reset() {
        ActiveMaterials.swap(new ActiveMaterials.State(new MaterialRegistry()));
    }

    private SectionStoreManager mgr(Path dir) {
        SectionStoreManager m = new SectionStoreManager();
        m.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        m.onChunkLoad(DIM, 0, 0);
        return m;
    }

    private ColumnWriteBack writeBack(SectionStoreManager m) {
        Supplier<FluidReconciler> rec = () -> (FluidReconciler) entry -> { };
        Supplier<PhaseChanger> phase = () -> (PhaseChanger) entry -> { };
        return new ColumnWriteBack(m, new CellMaterialTracker(), new ActiveSet(), rec, phase, () -> LUT);
    }

    /** Seed the stored bedrock cell at 290 K and mark it as "seen by the snapshot". */
    private SectionStore seedAndSnapshot(SectionStoreManager m) {
        SectionStore store = m.store(DIM);
        SectionData d = store.get(KEY);
        d.setMaterialAt(CELL, BEDROCK);
        d.setMass(CELL, 3000f);
        d.setEnthalpy(CELL, E_290);
        store.put(KEY, d);
        d.markSnapshot(); // the cycle's snapshot read this state
        return store;
    }

    /** A stale ColumnResult carrying the PRE-edit engine output (290 K) for the bedrock cell. */
    private ColumnResult staleResult() {
        int n = RegionMarshaller.CHUNK_N;
        char[] matIx = new char[n];
        float[] mass = new float[n];
        float[] temp = new float[n];
        float[] enth = new float[n];
        int ci = RegionMarshaller.colIdx(0, 0, 0); // world (0,-64,0) -> engineY 0
        matIx[ci] = BEDX; mass[ci] = 3000f; temp[ci] = 290f; enth[ci] = E_290;
        return new ColumnResult(matIx, mass, temp,
                new float[n], new float[n], new float[n], new float[n], new float[n], enth);
    }

    private ThermalWorld.ColumnEntry entry() {
        int n = RegionMarshaller.CHUNK_N;
        char[] matIx = new char[n];
        float[] mass = new float[n];
        int ci = RegionMarshaller.colIdx(0, 0, 0);
        matIx[ci] = BEDX; mass[ci] = 3000f;
        ColumnTask task = new ColumnTask(0, 0, matIx, mass, new float[n]);
        return new ThermalWorld.ColumnEntry(DIM, 0, 0, task);
    }

    @Test
    void editAfterSnapshotSurvivesStaleWriteBack(@TempDir Path dir) {
        SectionStoreManager m = mgr(dir);
        SectionStore store = seedAndSnapshot(m);

        // /orge set lands AFTER the snapshot: bumps the edit epoch.
        SectionData edited = store.get(KEY);
        edited.setEnthalpy(CELL, E_300);
        edited.markExternalEdit();
        store.put(KEY, edited);

        writeBack(m).writeBackColumn(entry(), staleResult());

        assertEquals(E_300, store.get(KEY).enthalpyAt(CELL), 1f,
                "the /orge set (300 K) must survive the stale 290 K write-back");
    }

    @Test
    void unEditedSectionStillWritesBack(@TempDir Path dir) {
        // Control: no external edit after the snapshot -> the write-back persists normally.
        SectionStoreManager m = mgr(dir);
        SectionStore store = seedAndSnapshot(m);

        writeBack(m).writeBackColumn(entry(), staleResult());

        assertEquals(E_290, store.get(KEY).enthalpyAt(CELL), 1f,
                "with no mid-cycle edit the engine result is persisted as usual");
    }
}
