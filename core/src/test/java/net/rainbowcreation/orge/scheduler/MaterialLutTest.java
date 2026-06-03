package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterialLutTest {

    private static Material mat(String id) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(1f).thermalConductivity(1f).molarMass(1f)
                .minMass(0f).maxMass(1f).viscosity(0f)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    private static MaterialLut view(Material... reals) {
        List<Material> ordered = new java.util.ArrayList<>();
        ordered.add(MaterialTable.VACUUM);
        for (Material m : reals) ordered.add(m);
        List<Material> immut = List.copyOf(ordered);
        return new MaterialLut(immut, MaterialTable.slots(immut));
    }

    @Test
    void slotZeroIsVacuum() {
        MaterialLut lut = view();
        assertEquals(MaterialTable.VACUUM, lut.materials().get(0));
        assertEquals((char) 0, lut.indexOf(MaterialTable.VACUUM));
    }

    @Test
    void indexOfReturnsFixedSlotNotInsertionOrder() {
        Material water = mat("orge:water");
        Material air = mat("orge:air");
        List<Material> ordered = List.of(MaterialTable.VACUUM, air, water);
        MaterialLut lut = new MaterialLut(ordered, MaterialTable.slots(ordered));
        assertEquals((char) 1, lut.indexOf(air));
        assertEquals((char) 2, lut.indexOf(water));
    }

    @Test
    void indexOfUnknownIdIsVacuumSentinel() {
        MaterialLut lut = view(mat("orge:water"));
        assertEquals((char) 0, lut.indexOf(Identifier.parse("orge:never_registered")));
    }

    @Test
    void indexOfNeverAppends() {
        MaterialLut lut = view(mat("orge:water"));
        int before = lut.materials().size();
        lut.indexOf(Identifier.parse("orge:unknown"));
        assertEquals(before, lut.materials().size());
    }
}
