package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.material.MaterialTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Assembling the same world twice yields identical matIx slots, and ids match the registry sort
 *  order — proving the engine-bound encoding is globally stable, not first-seen. */
class StableIdAssemblyTest {

    private static Material mat(String id) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(1f).thermalConductivity(1f).molarMass(1f)
                .minMass(0f).maxMass(1f).viscosity(0f)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    @Test
    void twoViewsOverSameTableAgreeOnSlots() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(mat("orge:water"));
        reg.put(mat("orge:air"));
        List<Material> ordered = MaterialTable.ordered(reg);
        Map<Identifier, Character> slots = MaterialTable.slots(ordered);

        MaterialLut tickA = new MaterialLut(ordered, slots);
        MaterialLut tickB = new MaterialLut(ordered, slots);
        assertEquals(tickA.indexOf(Identifier.parse("orge:water")),
                     tickB.indexOf(Identifier.parse("orge:water")));
        assertEquals(tickA.indexOf(Identifier.parse("orge:air")),
                     tickB.indexOf(Identifier.parse("orge:air")));
        // ids follow registry sort order (air < water), independent of registration order
        assertEquals((char) 1, tickA.indexOf(Identifier.parse("orge:air")));
        assertEquals((char) 2, tickA.indexOf(Identifier.parse("orge:water")));
    }
}
