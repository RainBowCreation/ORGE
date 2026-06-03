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

    @Test void readsLegacyV1AsMaterialUnknown() throws Exception {
        // Hand-build a v1 blob (version byte 1, one UNIFORM section, no material layer).
        byte[] v1 = LegacyV1.uniformColumn(3, 290f, 1000f); // test helper writing the OLD format
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(v1);
        assertFalse(back.get(3).hasMaterials()); // material-unknown: reconstruct from block later
    }
}
