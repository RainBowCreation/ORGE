package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.command.ServerCellStore;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.phase.MinecraftPhaseChanger;
import net.rainbowcreation.orge.phase.SourcePinPlanner;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #54 (law §7): an ambient section MATERIALIZED and persisted by a live entry point must reach the
 * engine with every untouched cell at {@code E = m·h(T_ambient)} of its seeded mass and real (first-touch)
 * species — never the ambient Kelvin parked in the enthalpy slot (E = 285 "J" ⇒ T ≈ 0 K), and never an
 * {@code orge:vacuum} identity fabricated for the cells the entry point did not touch.
 */
class SectionCellsReaderTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier STONE_ID = Identifier.fromNamespaceAndPath("minecraft", "stone");
    private static final List<Material> SLOTS =
            List.of(TestMaterials.voidMat(), TestMaterials.water(), TestMaterials.air(), TestMaterials.stone());
    private static final MaterialLut LUT = TestMaterials.lutOf(SLOTS);
    private static final MaterialRegistry REG = TestMaterials.registryOf(SLOTS);
    private static final Function<Identifier, Material> LOOKUP = id -> REG.get(id).orElse(null);

    /** Biome ambient the snapshot seeds with — deliberately != the store's FALLBACK 285 K. */
    private static final float AMBIENT_K = 290f;
    private static final int SECTION_Y = 4;
    private static final SubchunkKey KEY = new SubchunkKey(0, SECTION_Y, 0);
    /** The one cell each entry point touches (pinned source / placed block / /orge set target). */
    private static final int TOUCHED = 3 + 16 * 5 + 256 * 7;

    /** Live blocks: the lower half of every section is stone, the upper half air. */
    private static final GeometryAssembler.CellMaterials LIVE =
            i -> ((i >> 4) & 15) < 8 ? TestMaterials.stone() : TestMaterials.air();

    @TempDir
    Path dir;

    private SectionStoreManager loaded() {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0);
        return mgr;
    }

    private static ColumnTask assemble(SectionStore store) {
        GeometryAssembler.Geometry geo = GeometryAssembler.assemble(LIVE, LUT);
        return ColumnAssembler.assemble(0, 0, LUT, REG, (cx, cz, sy) ->
                SectionCellsReader.read(store, new SubchunkKey(cx, sy, cz), geo, LIVE, LOOKUP,
                        new char[SectionData.CELLS], new float[SectionData.CELLS], AMBIENT_K));
    }

    private static int colIdx(int sectionCell) {
        int x = sectionCell & 15, sy = (sectionCell >> 4) & 15, z = sectionCell >> 8;
        return RegionMarshaller.colIdx(x, SECTION_Y * 16 + sy + 64, z);
    }

    /** Every untouched cell of the materialized section: real species + seeded mass + E at ambient T. */
    private static void assertAmbientEnthalpy(SectionStore store, String path) {
        assertTrue(store.hasSection(KEY), path + ": the entry point must have persisted the section");
        ColumnTask t = assemble(store);
        for (int i = 0; i < SectionData.CELLS; i++) {
            if (i == TOUCHED) continue;
            int ci = colIdx(i);
            Material live = LIVE.at(i);
            assertEquals(LUT.indexOf(live), t.matIx()[ci],
                    path + ": untouched cell " + i + " keeps its live species (not orge:vacuum)");
            float seeded = t.mass()[ci];
            assertEquals(live.defaultMass(), seeded, 1e-4f, path + ": untouched cell " + i + " seeds its rest mass");
            double expect = EnthalpyCurve.cellE(seeded, live, LOOKUP, AMBIENT_K);
            assertEquals(expect, t.enthalpy()[ci], Math.abs(expect) * 1e-6,
                    path + ": untouched cell " + i + " E = m·h(T_ambient), not a Kelvin in the E slot");
            assertEquals(AMBIENT_K, EnthalpyCurve.deriveT(t.enthalpy()[ci], seeded, live, LOOKUP, -1f), 1e-2f,
                    path + ": untouched cell " + i + " derives the ambient T, not ≈0 K");
        }
    }

    @Test
    void pinnedSourceMaterializationSeedsAmbientEnthalpy() {
        SectionStore store = loaded().store(DIM);
        MinecraftPhaseChanger.pinSources(store, KEY, List.of(new SourcePinPlanner.Reset(TOUCHED, 1200f)), LOOKUP);
        assertAmbientEnthalpy(store, "pinned source");
    }

    @Test
    void setMaterialAtMaterializationSeedsAmbientEnthalpy() {
        SectionStore store = loaded().store(DIM);
        store.setMaterialAt(KEY.cx(), KEY.cz(), SECTION_Y, TOUCHED, STONE_ID);
        assertAmbientEnthalpy(store, "setMaterialAt");
    }

    @Test
    void orgeSetMaterializationSeedsAmbientEnthalpy() {
        SectionStoreManager mgr = loaded();
        new ServerCellStore(mgr, null, (dim, key, cell) -> LIVE.at(cell).id())
                .write(DIM, KEY, TOUCHED, 400f, null);
        assertAmbientEnthalpy(mgr.store(DIM), "/orge set");
    }

    @Test
    void materializedSectionRoundTripsThroughRegionSaveLoad() {
        for (Consumer<SectionStore> entry : List.<Consumer<SectionStore>>of(
                s -> MinecraftPhaseChanger.pinSources(s, KEY, List.of(new SourcePinPlanner.Reset(TOUCHED, 1200f)), LOOKUP),
                s -> s.setMaterialAt(KEY.cx(), KEY.cz(), SECTION_Y, TOUCHED, STONE_ID))) {
            SectionStoreManager mgr = new SectionStoreManager();
            Path level = dir.resolve("level" + System.nanoTime());
            mgr.onLevelLoad(DIM, level, AmbientProvider.FALLBACK);
            mgr.onChunkLoad(DIM, 0, 0);
            entry.accept(mgr.store(DIM));
            ColumnTask before = assemble(mgr.store(DIM));
            mgr.onChunkUnload(DIM, 0, 0);               // flush the materialized section to the region file
            mgr.onChunkLoad(DIM, 0, 0);                 // ... and read it back
            SectionStore reloaded = mgr.store(DIM);
            assertTrue(reloaded.hasSection(KEY), "the materialized section survives save/load");
            SectionData d = reloaded.get(KEY);
            for (int i = 0; i < SectionData.CELLS; i++) {
                if (i == TOUCHED) continue;
                assertNotEquals(SectionData.DEFAULT_AMBIENT_K, (float) d.enthalpyAt(i),
                        "persisted E of cell " + i + " is never the ambient Kelvin");
            }
            assertAmbientEnthalpy(reloaded, "reloaded");
            ColumnTask after = assemble(reloaded);
            for (int i = 0; i < SectionData.CELLS; i++) {
                int ci = colIdx(i);
                assertEquals(before.enthalpy()[ci], after.enthalpy()[ci], Math.abs(before.enthalpy()[ci]) * 1e-6,
                        "engine-bound E of cell " + i + " is unchanged by the round trip");
                assertEquals(before.matIx()[ci], after.matIx()[ci], "species of cell " + i + " survives the round trip");
            }
            mgr.onLevelUnload(DIM);
        }
    }
}
