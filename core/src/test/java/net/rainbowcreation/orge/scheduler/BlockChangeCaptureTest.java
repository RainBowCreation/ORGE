package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.RegionStore;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The capture material a block change reads (issue #63): a piston's {@code moving_piston} carrier is no
 *  placement, so a push never injects {@code orge:generic_solid} into the cell it moves through. And the
 *  capture of a non-air live block edit ({@link BlockChangeCapture#captureEdit}): vanilla spread in an
 *  unmanaged section is not a placement (issue #64), a source placed there still is. */
class BlockChangeCaptureTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier MOVING_PISTON = Identifier.fromNamespaceAndPath("minecraft", "moving_piston");
    private static final Identifier STONE_BLOCK = Identifier.fromNamespaceAndPath("minecraft", "stone");
    private static final int CX = 3, CZ = -2, SECTION_Y = 4;
    private static final int LX = 5, LY = 9, LZ = 11;
    private static final int SECTION_CELL = LX + 16 * LY + 256 * LZ;
    private static final int ENGINE_CELL = ColumnSectionCodec.colIdx(LX, SECTION_Y, LY, LZ);

    @TempDir
    Path world;

    /** orge:stone, the first-touch of a minecraft:stone block (TestMaterials.stone() is keyed by the block id). */
    private static Material stone() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "stone"))
                .thermalConductivity(1.0f).heatCapacity(840f).molarMass(0f)
                .defaultMass(2000f).defaultTemperature(Float.NaN)
                .minTemp(0f).maxTemp(9999f)
                .build();
    }

    private static MaterialRegistry registry() {
        return TestMaterials.registryOf(List.of(TestMaterials.air(), TestMaterials.water(), stone()));
    }

    @Test
    void movingPistonCapturesNoMaterial() {
        assertNull(BlockChangeCapture.placedMaterial(MOVING_PISTON, registry()),
                "moving_piston is a transient carrier, not orge:generic_solid");
    }

    @Test
    void unboundBlockStillFallsBackToGenericSolid() {
        Material m = BlockChangeCapture.placedMaterial(
                Identifier.fromNamespaceAndPath("minecraft", "obsidian"), registry());
        assertEquals(MaterialRegistry.FALLBACK_ID, m.id());
    }

    /** A stone block pushed into a water cell: the cell first shows moving_piston, then stone. Only the
     *  landing is a displacement, and it places stone — no generic_solid intent is ever queued. */
    @Test
    void pistonPushIntoWaterQueuesOnlyTheLandedBlock() {
        MaterialRegistry reg = registry();
        PendingInjections q = new PendingInjections();
        Material water = TestMaterials.water();
        int cell = 100;

        BlockChangeCapture.captureOrCancelStaleRemoval(q, DIM, 0, 0, cell,
                BlockChangeCapture.placedMaterial(MOVING_PISTON, reg), water, 295f);
        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty(), "the in-flight carrier displaces nothing");

        BlockChangeCapture.captureOrCancelStaleRemoval(q, DIM, 0, 0, cell,
                BlockChangeCapture.placedMaterial(STONE_BLOCK, reg), water, 295f);
        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size());
        assertEquals(stone().id(), got.get(0).species());
        assertEquals(stone().defaultMass(), got.get(0).mass());
    }

    /** A loaded column with no stored section: the unmanaged section vanilla flow still ticks in. */
    private SectionStore unmanagedStore() {
        SectionStore store = new SectionStore(new RegionStore(world), AmbientProvider.FALLBACK);
        store.loadColumn(CX, CZ);
        return store;
    }

    /** Issue #64: vanilla water spreading through an unmanaged section reaches the capture once per spread
     *  cell. The cell is untracked, so it was read as a placement and credited water's whole defaultMass —
     *  1000 kg per cell — and the section was materialized (the fluid policy then reads it as managed). */
    @Test
    void vanillaSpreadInUnmanagedSectionCreditsNoMassAndMaterializesNothing() {
        PendingInjections q = new PendingInjections();
        SectionStore store = unmanagedStore();

        BlockChangeCapture.captureEdit(q, store, DIM, CX, CZ, SECTION_Y, SECTION_CELL, ENGINE_CELL,
                TestMaterials.water(), null, true, 295f);

        assertTrue(q.peekColumn(DIM, CX, CZ).isEmpty(), "a spread cell is not a placement: no kg credited");
        assertFalse(store.hasSection(new SubchunkKey(CX, SECTION_Y, CZ)),
                "the unmanaged section stays unmanaged (vanilla keeps owning its flow)");
    }

    /** A spread cell is never credited, however many cells the vanilla flow reaches. */
    @Test
    void manySpreadCellsCreditNothing() {
        PendingInjections q = new PendingInjections();
        SectionStore store = unmanagedStore();

        for (int x = 0; x < 16; x++) {
            BlockChangeCapture.captureEdit(q, store, DIM, CX, CZ, SECTION_Y, x + 16 * LY + 256 * LZ,
                    ColumnSectionCodec.colIdx(x, SECTION_Y, LY, LZ), TestMaterials.water(), null, true, 295f);
        }

        float credited = 0f;
        for (PendingInjections.Intent in : q.peekColumn(DIM, CX, CZ)) {
            credited += in.mass();
        }
        assertEquals(0f, credited);
    }

    /** A source placed in an unmanaged section (a bucket) is still a placement: its defaultMass is
     *  enqueued and its durable identity recorded (the vanish-race fix is untouched). */
    @Test
    void sourcePlacedInUnmanagedSectionIsStillAPlacement() {
        PendingInjections q = new PendingInjections();
        SectionStore store = unmanagedStore();

        BlockChangeCapture.captureEdit(q, store, DIM, CX, CZ, SECTION_Y, SECTION_CELL, ENGINE_CELL,
                TestMaterials.water(), null, false, 295f);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, CX, CZ);
        assertEquals(1, got.size());
        assertEquals(TestMaterials.water().id(), got.get(0).species());
        assertEquals(TestMaterials.water().defaultMass(), got.get(0).mass());
        assertEquals(ENGINE_CELL, got.get(0).cell());
        assertEquals(TestMaterials.water().id(), store.materialAt(CX, CZ, SECTION_Y, SECTION_CELL));
    }

    @Test
    void airEditRemovesAPlacementStillQueuedOverRecordedAir() {
        Identifier air = Identifier.fromNamespaceAndPath("orge", "air");
        PendingInjections q = new PendingInjections();
        assertFalse(BlockChangeCapture.airEditRemoves(q, DIM, CX, CZ, ENGINE_CELL, air),
                "air over recorded air with nothing queued is a steady repaint, not a removal");

        // `fill stone` queued the stone; the recorded incumbent still reads the air the engine last wrote
        q.enqueue(DIM, CX, CZ, ENGINE_CELL, Identifier.fromNamespaceAndPath("orge", "stone"), 2000f, 290f);
        assertTrue(BlockChangeCapture.airEditRemoves(q, DIM, CX, CZ, ENGINE_CELL, air),
                "`fill air` right after `fill stone` must remove the still-queued stone");
    }
}
