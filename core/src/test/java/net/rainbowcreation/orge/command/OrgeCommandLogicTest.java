package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OrgeCommandLogicTest {

    static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    static final int MIN_Y = -64, MAX_Y = 320; // exclusive max

    /** Array-backed fake view (FULL semantics; ambient flag set explicitly). */
    static SectionView view(float temp, float mass, boolean ambient) {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, temp);
        java.util.Arrays.fill(m, mass);
        return arrayView(t, m, ambient, SectionData.Form.UNIFORM);
    }

    static SectionView arrayView(float[] t, float[] m, boolean ambient, SectionData.Form form) {
        return new SectionView() {
            public float tempAt(int c) { return t[c]; }
            public float massAt(int c) { return m[c]; }
            public Identifier material(int c) { return Identifier.fromNamespaceAndPath("orge", "air"); }
            public SectionData.Form form() { return form; }
            public boolean ambient() { return ambient; }
        };
    }

    /**
     * One in-memory {@link CellStore} standing in for the whole read/write seam: reads come from
     * {@code data} (a null map -> always empty, i.e. "no store"); {@link #isLoaded} from a set of loaded
     * columns; writes are recorded as {@link Write} tuples. The store applies mass-before-temp internally
     * (see {@link ServerCellStore}); here we only assert the logic forwarded the right cell/temp/mass.
     */
    static final class FakeCellStore implements CellStore {
        final Map<SubchunkKey, SectionView> data;
        final java.util.Set<String> loaded = new java.util.HashSet<>();
        final List<Write> writes = new ArrayList<>();

        FakeCellStore(Map<SubchunkKey, SectionView> data) { this.data = data; }

        FakeCellStore load(int cx, int cz) { loaded.add(cx + "," + cz); return this; }

        public Optional<SectionView> read(Identifier dim, SubchunkKey key) {
            return data == null ? Optional.empty() : Optional.ofNullable(data.get(key));
        }

        public boolean isLoaded(Identifier dim, SubchunkKey key) {
            return loaded.contains(key.cx() + "," + key.cz());
        }

        public void write(Identifier dim, SubchunkKey key, int cell, float tempK, Float massKg) {
            writes.add(new Write(key, cell, tempK, massKg));
        }

        long massWrites() { return writes.stream().filter(w -> w.massKg() != null).count(); }

        record Write(SubchunkKey key, int cell, float tempK, Float massKg) {}
    }

    static OrgeCommandLogic logic(CellStore store, int range) {
        return new OrgeCommandLogic(store, () -> range);
    }

    /** Read-only store backed by an in-memory map (no writes exercised). */
    static FakeCellStore reads(Map<SubchunkKey, SectionView> data) {
        return new FakeCellStore(data);
    }

    static OrgeCommandLogic.Request get(int x, int y, int z, boolean op, SubchunkKey src) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.GET, DIM,
                x, y, z, x, y, z, null, null, op, src, MIN_Y, MAX_Y);
    }

    @Test
    void getReadsAmbientBaselineWithAnnotation() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(reads(data), 4);

        OrgeCommandLogic.Response r = logic.run(get(1, 2, 3, true, null));

        assertTrue(r.ok());
        assertEquals(1, r.lines().size());
        assertTrue(r.lines().get(0).contains("285.00 K"), r.lines().get(0));
        assertTrue(r.lines().get(0).contains("ambient"), "ambient baseline is annotated");
    }

    @Test
    void getReadSourceChainPrefersFirstHit() {
        SubchunkKey key = new SubchunkKey(0, 0, 0);
        Map<SubchunkKey, SectionView> client = new HashMap<>();
        client.put(key, view(500f, 0f, false));            // "client cache" - fresher
        Map<SubchunkKey, SectionView> server = new HashMap<>();
        server.put(key, view(285f, 0f, true));             // server fallback
        // The read chain lives in the CellStore now: first present source wins.
        CellStore store = new ReadChainCellStore(List.of(reads(client), reads(server)));
        OrgeCommandLogic logic = logic(store, 4);

        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));

        assertTrue(r.lines().get(0).contains("500.00 K"), "first source wins: " + r.lines().get(0));
    }

    @Test
    void getFallsBackToSecondSourceWhenFirstMisses() {
        SubchunkKey key = new SubchunkKey(0, 0, 0);
        Map<SubchunkKey, SectionView> server = new HashMap<>();
        server.put(key, view(285f, 0f, true));
        CellStore store = new ReadChainCellStore(List.of(reads(new HashMap<>()), reads(server)));
        OrgeCommandLogic logic = logic(store, 4);

        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));

        assertTrue(r.lines().get(0).contains("285.00 K"));
    }

    @Test
    void getNoStoreForDimensionFails() {
        OrgeCommandLogic logic = logic(reads(null), 4); // null map -> store serves nothing
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("no ORGE data"), r.lines().get(0));
    }

    @Test
    void getYOutOfBuildHeightFails() {
        OrgeCommandLogic logic = logic(reads(new HashMap<>()), 4);
        OrgeCommandLogic.Response r = logic.run(get(0, 999, 0, true, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("build height"), r.lines().get(0));
    }

    // ---- proximity gate ----

    @Test
    void nonOpInSphereAllowed() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(reads(data), 4);
        // target section (0,0,0); source section (1,0,0): d2=1 <= (4-1)^2
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, new SubchunkKey(1, 0, 0)));
        assertTrue(r.ok(), r.lines().toString());
    }

    @Test
    void nonOpOutOfSphereDenied() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(reads(data), 2); // range 2 -> r=1 -> r2=1
        // source section (5,0,0): d2=25 > 1
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, new SubchunkKey(5, 0, 0)));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("out of range"), r.lines().get(0));
    }

    @Test
    void nonOpWithoutSourcePositionDenied() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(reads(data), 4);
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("out of range"));
    }

    @Test
    void opReadsAnywhere() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(100, 0, 100), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(reads(data), 2);
        OrgeCommandLogic.Response r = logic.run(get(1600, 0, 1600, true, new SubchunkKey(0, 0, 0)));
        assertTrue(r.ok());
    }

    static OrgeCommandLogic.Request section(int x, int y, int z, boolean op, SubchunkKey src) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.SECTION, DIM,
                x, y, z, x, y, z, null, null, op, src, MIN_Y, MAX_Y);
    }

    @Test
    void sectionSummarizesUniformAmbient() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(reads(data), 4);

        OrgeCommandLogic.Response r = logic.run(section(0, 0, 0, true, null));

        assertTrue(r.ok());
        String joined = String.join("\n", r.lines());
        assertTrue(joined.contains("(ambient)"), joined);
        assertTrue(joined.contains("285.00 / 285.00 / 285.00 K"), joined);
        assertTrue(joined.contains("0 / 4096"), "uniform -> 0 non-uniform cells: " + joined);
    }

    @Test
    void sectionReportsGradientMinAvgMaxAndNonUniformCount() {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, 300f);
        t[0] = 300f;       // cell0 baseline
        t[1] = 400f;       // one hotter cell
        t[2] = 200f;       // one colder cell
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), arrayView(t, m, false, SectionData.Form.FULL));
        OrgeCommandLogic logic = logic(reads(data), 4);

        OrgeCommandLogic.Response r = logic.run(section(0, 0, 0, true, null));

        String joined = String.join("\n", r.lines());
        assertTrue(joined.contains("200.00 / "), "min reflects coldest: " + joined);
        assertTrue(joined.contains(" / 400.00 K"), "max reflects hottest: " + joined);
        assertTrue(joined.contains("2 / 4096"), "two cells differ from cell0: " + joined);
        assertFalse(joined.contains("(ambient)"), "stored section not annotated ambient");
    }

    @Test
    void sectionProximityGatedForNonOp() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(reads(data), 2);
        OrgeCommandLogic.Response r = logic.run(section(0, 0, 0, false, new SubchunkKey(9, 0, 0)));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("out of range"));
    }

    // ---- set ----

    static OrgeCommandLogic.Request set(int x, int y, int z, Float k, Float mass, boolean op) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.SET, DIM,
                x, y, z, x, y, z, k, mass, op, null, MIN_Y, MAX_Y);
    }

    @Test
    void setRequiresOperator() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 0, 0, 400f, null, false));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("operator"), r.lines().get(0));
        assertTrue(store.writes.isEmpty(), "no write when denied");
    }

    @Test
    void setForwardsTempAndNewMassInOneAtomicWrite() {
        // Regression intent (a633e52): the temperature must encode at the NEW mass, not the old one
        // (set 300 K @ 3000 kg over a 1.2 kg cell would otherwise read 0.12 K). At this seam the command
        // hands BOTH the temperature and the new mass to the store in a single write() so the store can
        // apply mass before encoding temperature; the mass-before-encode ORDER itself is the store's
        // job and is round-trip-verified in ServerStoreSeamTest.massBeforeTempEncodesTemperatureAtTheNewMass.
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 0, 0, 300f, 3000f, true));
        assertTrue(r.ok(), r.lines().toString());
        assertEquals(1, store.writes.size(), "one atomic write");
        FakeCellStore.Write w = store.writes.get(0);
        assertEquals(300f, w.tempK(), "temperature forwarded");
        assertEquals(3000f, w.massKg(), "new mass forwarded in the SAME write, so the store encodes at it");
    }

    @Test
    void setWritesTempLeavesMassUnchangedWhenOmitted() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(set(1, 2, 3, 400f, null, true));
        assertTrue(r.ok(), r.lines().toString());
        assertEquals(1, store.writes.size());
        assertEquals(400f, store.writes.get(0).tempK());
        assertNull(store.writes.get(0).massKg(), "mass omitted -> null (store leaves mass unchanged)");
        assertTrue(r.lines().get(0).contains("mass unchanged"), r.lines().get(0));
    }

    @Test
    void setWritesTempAndMassWhenProvided() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 0, 0, 400f, 1000f, true));
        assertTrue(r.ok());
        assertEquals(1, store.writes.size());
        assertEquals(1000f, store.writes.get(0).massKg());
    }

    @Test
    void setNotLoadedFails() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()); // nothing loaded
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 0, 0, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("not loaded"), r.lines().get(0));
        assertTrue(store.writes.isEmpty());
    }

    @Test
    void setYOutOfRangeFails() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 999, 0, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("build height"));
    }

    static OrgeCommandLogic.Request fill(int x1, int y1, int z1, int x2, int y2, int z2,
                                         Float k, Float mass, boolean op) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.FILL, DIM,
                x1, y1, z1, x2, y2, z2, k, mass, op, null, MIN_Y, MAX_Y);
    }

    @Test
    void fillRequiresOperator() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 1, 1, 1, 400f, null, false));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("operator"));
    }

    @Test
    void fillWritesEveryCellInBoxAndReportsCount() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        // 3x3x3 box at origin = 27 cells, all in column (0,0)
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 2, 2, 2, 400f, null, true));
        assertTrue(r.ok(), r.lines().toString());
        assertEquals(27, store.writes.size());
        assertTrue(r.lines().get(0).contains("filled 27 cells"), r.lines().get(0));
    }

    @Test
    void fillWritesMassWhenProvided() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 1, 0, 0, 400f, 500f, true));
        assertTrue(r.ok());
        assertEquals(2, store.writes.size());
        assertEquals(2, store.massWrites(), "both cells carry the mass in their atomic write");
    }

    @Test
    void fillSkipsUnloadedColumnsAndReports() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0); // only column (0,0) loaded
        OrgeCommandLogic logic = logic(store, 4);
        // span x 0..16 crosses into column (1,0) at x=16, which is not loaded
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 16, 0, 0, 400f, null, true));
        assertTrue(r.ok());
        assertEquals(16, store.writes.size(), "x=0..15 loaded, x=16 skipped");
        assertTrue(r.lines().get(0).contains("1 skipped"), r.lines().get(0));
    }

    @Test
    void fillAllUnloadedFails() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()); // nothing loaded
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 1, 1, 1, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("0 cells"), r.lines().get(0));
        assertTrue(store.writes.isEmpty());
    }

    @Test
    void fillOverCapRejected() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        // 33x33x33 = 35937 > 32768 cap
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 32, 32, 32, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("too large"), r.lines().get(0));
        assertTrue(store.writes.isEmpty(), "rejected before any write");
    }

    @Test
    void fillYOutOfRangeFails() {
        FakeCellStore store = new FakeCellStore(new HashMap<>()).load(0, 0);
        OrgeCommandLogic logic = logic(store, 4);
        OrgeCommandLogic.Response r = logic.run(fill(0, -100, 0, 0, 999, 0, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("build height"));
    }
}
