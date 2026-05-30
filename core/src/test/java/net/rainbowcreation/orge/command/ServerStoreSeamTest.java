package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServerStoreSeamTest {

    static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    private SectionStoreManager managerWithLoadedColumn(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0); // load column (0,0)
        return mgr;
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

        sink.writeTemp(DIM, key, 5, 400f);

        SectionView after = src.section(DIM, key).orElseThrow();
        assertFalse(after.ambient(), "after write it is stored");
        assertEquals(400f, after.tempAt(5), 0.001f);
    }

    @Test
    void writeTempThenMassDoNotClobber(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        sink.writeTemp(DIM, key, 7, 350f);
        sink.writeMass(DIM, key, 7, 1000f);

        SectionView v = src.section(DIM, key).orElseThrow();
        assertEquals(350f, v.tempAt(7), 0.001f);
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
