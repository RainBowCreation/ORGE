package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaterialTableTest {

    private static Material mat(String id) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(1f).thermalConductivity(1f).molarMass(1f)
                .minMass(0f).maxMass(1f).viscosity(0f)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    @Test
    void slotZeroIsVacuum() {
        MaterialRegistry reg = new MaterialRegistry();
        List<Material> ordered = MaterialTable.ordered(reg);
        assertEquals(MaterialTable.VACUUM, ordered.get(0), "slot 0 is the VACUUM sentinel");
    }

    @Test
    void realMaterialsSortedByNamespacedId() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(mat("orge:water"));
        reg.put(mat("orge:air"));
        reg.put(mat("orge:lava"));
        List<Material> ordered = MaterialTable.ordered(reg);
        assertEquals(MaterialTable.VACUUM.id(), ordered.get(0).id());
        assertEquals("orge:air",   ordered.get(1).id().toString());
        assertEquals("orge:lava",  ordered.get(2).id().toString());
        assertEquals("orge:water", ordered.get(3).id().toString());
    }

    @Test
    void slotsMapMaterialIdToFixedIndex() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(mat("orge:water"));
        reg.put(mat("orge:air"));
        List<Material> ordered = MaterialTable.ordered(reg);
        Map<Identifier, Character> slots = MaterialTable.slots(ordered);
        assertEquals((char) 0, slots.get(MaterialTable.VACUUM.id()));
        assertEquals((char) 1, slots.get(Identifier.parse("orge:air")));
        assertEquals((char) 2, slots.get(Identifier.parse("orge:water")));
    }

    @Test
    void orderingIsStableAcrossRebuilds() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(mat("orge:water"));
        reg.put(mat("orge:lava"));
        reg.put(mat("orge:air"));
        List<Material> a = MaterialTable.ordered(reg);
        List<Material> b = MaterialTable.ordered(reg);
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).id(), b.get(i).id(), "slot " + i + " stable");
    }
}
