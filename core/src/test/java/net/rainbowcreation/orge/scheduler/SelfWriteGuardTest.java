package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionStoreManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ORGE's own world writes (the reconciler painting an engine species) must never be captured as a
 * player placement/removal — that capture is what injected fresh 290 K stone over solidifying lava.
 */
class SelfWriteGuardTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void guardIsRaisedOnlyForTheDurationOfTheWrite() {
        assertFalse(LiveMaterials.inSelfWrite());
        boolean[] seen = new boolean[1];
        LiveMaterials.selfWrite(() -> seen[0] = LiveMaterials.inSelfWrite());
        assertTrue(seen[0]);
        assertFalse(LiveMaterials.inSelfWrite());
        assertThrows(IllegalStateException.class,
                () -> LiveMaterials.selfWrite(() -> { throw new IllegalStateException("boom"); }));
        assertFalse(LiveMaterials.inSelfWrite(), "guard must drop even when the write throws");
    }

    @Test
    void guardedBlockChangeSkipsCaptureButStillWakes() {
        AtomicInteger serverReads = new AtomicInteger();
        ActiveSet active = new ActiveSet();
        BlockChangeCapture capture = new BlockChangeCapture(new SectionStoreManager(),
                new CellMaterialTracker(), active, new PendingInjections(),
                () -> { serverReads.incrementAndGet(); return null; });

        LiveMaterials.selfWrite(() -> capture.wakeSink().wakeBlock(DIM, 1, 2, 3));
        assertEquals(0, serverReads.get(), "a guarded write must bail before any capture work");

        capture.wakeSink().wakeBlock(DIM, 1, 2, 3);
        assertEquals(1, serverReads.get(), "an unguarded write still reaches the capture");
    }
}
