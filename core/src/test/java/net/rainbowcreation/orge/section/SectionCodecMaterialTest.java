package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.NavigableMap;
import static org.junit.jupiter.api.Assertions.*;

class SectionCodecMaterialTest {
    private static Identifier id(String p) { return Identifier.fromNamespaceAndPath("orge", p); }

    @Test void roundTripsMaterialLayer() throws Exception {
        SectionData s = SectionData.uniform(290f, 1000f);
        s.setMaterialAt(0, id("stone"));
        s.setMaterialAt(42, id("water"));
        byte[] blob = SectionCodec.writeColumn(Map.of(3, s));
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(3);
        assertTrue(r.hasMaterials());
        assertEquals(id("stone"), r.materialAt(0));
        assertEquals(id("water"), r.materialAt(42));
        assertEquals(MaterialPalette.VACUUM_ID, r.materialAt(1));
    }

    @Test void rejectsLegacyV1() {
        // v1 blobs stored raw temperature with no material/momentum/pressure layers. Under the law §7
        // schema (v5) there is NO migration — a v1 blob must be rejected, not loaded. (T2 S3.)
        byte[] v1 = LegacyV1.uniformColumn(3, 290f, 1000f); // test helper writing the OLD format
        java.io.IOException ex = assertThrows(java.io.IOException.class,
                () -> SectionCodec.readColumn(v1), "v1 blob must be rejected (no migration)");
        assertTrue(ex.getMessage().contains("fresh world"),
                "reject message must tell the user to start a fresh world, was: " + ex.getMessage());
    }
}
