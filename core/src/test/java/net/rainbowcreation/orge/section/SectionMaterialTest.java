package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionMaterialTest {
    private static Identifier id(String p) { return Identifier.fromNamespaceAndPath("orge", p); }

    @Test void uniformSectionHasNoMaterialLayerUntilSet() {
        SectionData s = SectionData.uniform(290f, 1000f);
        assertFalse(s.hasMaterials());
    }

    @Test void settingACellPromotesAndStoresMaterial() {
        SectionData s = SectionData.uniform(290f, 1000f);
        s.setMaterialAt(5, id("water"));
        assertTrue(s.hasMaterials());
        assertEquals(id("water"), s.materialAt(5));
        // unset cells read the vacuum sentinel until written
        assertEquals(MaterialPalette.VACUUM_ID, s.materialAt(6));
    }

    @Test void paletteDedupesRepeatedMaterials() {
        SectionData s = SectionData.uniform(290f, 1000f);
        s.setMaterialAt(0, id("stone"));
        s.setMaterialAt(1, id("stone"));
        s.setMaterialAt(2, id("water"));
        assertEquals(id("stone"), s.materialAt(0));
        assertEquals(id("stone"), s.materialAt(1));
        assertEquals(id("water"), s.materialAt(2));
        assertEquals(3, s.palette().size()); // vacuum(0) + stone + water
    }
}
