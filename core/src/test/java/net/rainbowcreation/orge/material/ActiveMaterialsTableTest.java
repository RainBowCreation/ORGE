package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActiveMaterialsTableTest {

    private static Material mat(String id) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(1f).thermalConductivity(1f).molarMass(1f)
                .minMass(0f).maxMass(1f).viscosity(0f)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    @Test
    void stateExposesOrderedTableSlot0Vacuum() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(mat("orge:water"));
        ActiveMaterials.State s = new ActiveMaterials.State(reg);
        assertEquals(MaterialTable.VACUUM.id(), s.orderedMaterials().get(0).id());
        assertEquals((char) 0, s.materialSlots().get(MaterialTable.VACUUM.id()));
        assertEquals((char) 1, s.materialSlots().get(Identifier.parse("orge:water")));
    }

    @Test
    void swapAssignsMonotonicEpochs() {
        ActiveMaterials.State a = new ActiveMaterials.State(new MaterialRegistry());
        ActiveMaterials.State b = new ActiveMaterials.State(new MaterialRegistry());
        ActiveMaterials.swap(a);
        int ea = ActiveMaterials.current().lutEpoch();
        ActiveMaterials.swap(b);
        int eb = ActiveMaterials.current().lutEpoch();
        assertTrue(eb > ea, "each swap bumps the epoch (" + ea + " -> " + eb + ")");
    }
}
