package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import org.junit.jupiter.api.Test;

/** The capture material a block change reads (issue #63): a piston's {@code moving_piston} carrier is no
 *  placement, so a push never injects {@code orge:generic_solid} into the cell it moves through. */
class BlockChangeCaptureTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier MOVING_PISTON = Identifier.fromNamespaceAndPath("minecraft", "moving_piston");
    private static final Identifier STONE_BLOCK = Identifier.fromNamespaceAndPath("minecraft", "stone");

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
}
