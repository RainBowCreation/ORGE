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
            public SectionData.Form form() { return form; }
            public boolean ambient() { return ambient; }
        };
    }

    /** Read source backed by an in-memory map; absent key -> empty. A null map -> always empty (no store). */
    static ThermalReadSource source(Map<SubchunkKey, SectionView> data) {
        return (dim, key) -> data == null ? Optional.empty() : Optional.ofNullable(data.get(key));
    }

    static OrgeCommandLogic logic(List<ThermalReadSource> reads, ThermalWriteSink sink, int range) {
        return new OrgeCommandLogic(reads, sink, () -> range);
    }

    static OrgeCommandLogic.Request get(int x, int y, int z, boolean op, SubchunkKey src) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.GET, DIM,
                x, y, z, x, y, z, null, null, op, src, MIN_Y, MAX_Y);
    }

    @Test
    void getReadsAmbientBaselineWithAnnotation() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);

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
        OrgeCommandLogic logic = logic(List.of(source(client), source(server)), null, 4);

        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));

        assertTrue(r.lines().get(0).contains("500.00 K"), "first source wins: " + r.lines().get(0));
    }

    @Test
    void getFallsBackToSecondSourceWhenFirstMisses() {
        SubchunkKey key = new SubchunkKey(0, 0, 0);
        Map<SubchunkKey, SectionView> server = new HashMap<>();
        server.put(key, view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>()), source(server)), null, 4);

        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));

        assertTrue(r.lines().get(0).contains("285.00 K"));
    }

    @Test
    void getNoStoreForDimensionFails() {
        OrgeCommandLogic logic = logic(List.of(source(null)), null, 4);
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("no ORGE data"), r.lines().get(0));
    }

    @Test
    void getYOutOfBuildHeightFails() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);
        OrgeCommandLogic.Response r = logic.run(get(0, 999, 0, true, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("build height"), r.lines().get(0));
    }

    // ---- proximity gate ----

    @Test
    void nonOpInSphereAllowed() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);
        // target section (0,0,0); source section (1,0,0): d2=1 <= (4-1)^2
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, new SubchunkKey(1, 0, 0)));
        assertTrue(r.ok(), r.lines().toString());
    }

    @Test
    void nonOpOutOfSphereDenied() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 2); // range 2 -> r=1 -> r2=1
        // source section (5,0,0): d2=25 > 1
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, new SubchunkKey(5, 0, 0)));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("out of range"), r.lines().get(0));
    }

    @Test
    void nonOpWithoutSourcePositionDenied() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("out of range"));
    }

    @Test
    void opReadsAnywhere() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(100, 0, 100), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 2);
        OrgeCommandLogic.Response r = logic.run(get(1600, 0, 1600, true, new SubchunkKey(0, 0, 0)));
        assertTrue(r.ok());
    }
}
