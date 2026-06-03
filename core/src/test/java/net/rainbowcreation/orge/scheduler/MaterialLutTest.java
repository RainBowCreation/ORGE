package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterialLutTest {

    private static Material mat(String path, float k) {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", path))
                .thermalConductivity(k).heatCapacity(1000f).molarMass(0f)
                .defaultMass(2000f).defaultTemperature(Float.NaN)
                .build();
    }

    @Test
    void indexZeroIsVacuumWithZeroConductivity() {
        MaterialLut lut = new MaterialLut();
        List<Material> materials = lut.materials();
        assertEquals(1, materials.size(), "fresh LUT holds only the vacuum sentinel");
        assertEquals(0f, materials.get(0).thermalConductivity(), "vacuum must be inert (k=0)");
    }

    @Test
    void firstRealMaterialGetsIndexOne_andRepeatsReuseIt() {
        MaterialLut lut = new MaterialLut();
        char first = lut.indexOf(mat("stone", 2.5f));   // instance A
        char again = lut.indexOf(mat("stone", 2.5f));   // instance B — same id, different object
        assertEquals(1, first);
        assertEquals(1, again, "same material id deduplicates across distinct instances");
        assertEquals(2, lut.materials().size());
    }

    @Test
    void indexOfVacuumReturnsZeroAndDoesNotAppend() {
        MaterialLut lut = new MaterialLut();
        assertEquals(Identifier.fromNamespaceAndPath("orge", "vacuum"), MaterialLut.VACUUM.id());
        assertEquals(0, lut.indexOf(MaterialLut.VACUUM));
        assertEquals(1, lut.materials().size());
    }

    @Test
    void distinctMaterialsGetSequentialIndices() {
        MaterialLut lut = new MaterialLut();
        assertEquals(1, lut.indexOf(mat("stone", 2.5f)));
        assertEquals(2, lut.indexOf(mat("water", 0.6f)));
        assertEquals(3, lut.indexOf(mat("iron", 80f)));
        assertEquals(4, lut.materials().size());
    }
}
