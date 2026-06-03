package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineInjection;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** Draining an intent overrides the column cell back to its incumbent (so Java won't reseed the new
 *  species) and emits an EngineInjection that places the new species, resolved against the batch LUT. */
class InjectionDrainTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void drainOverridesIncumbentAndEmitsInjection() {
        // batch LUT: [0]=void, [1]=air, [2]=water
        Material air = TestMaterials.air();
        Material water = TestMaterials.water();
        List<Material> lut = List.of(TestMaterials.voidMat(), air, water);

        int cell = 3 + 16 * 70 + 6144 * 4;
        char[] matIx = new char[net.rainbowcreation.orge.engine.RegionMarshaller.CHUNK_N];
        float[] mass = new float[matIx.length];
        // live snapshot currently shows WATER at the cell (player placed it):
        matIx[cell] = 2; mass[cell] = 0f;

        // incumbent recorded for the cell = air, stored mass 1.2:
        float storedIncumbentMass = 1.2f;
        Identifier incumbentId = air.id();

        PendingInjections.Intent intent =
                new PendingInjections.Intent(DIM, 0, 0, cell, water.id(), 1000f, 290f);

        List<EngineInjection> out = new ArrayList<>();
        InjectionDrain.applyToColumn(
                /*columnId*/ 0, matIx, mass, lut,
                List.of(intent),
                /*incumbentSpeciesId*/ c -> incumbentId,           // resolver stub: cell -> air
                /*incumbentMass*/ c -> storedIncumbentMass,
                out);

        // cell overridden back to incumbent (air@1.2), NOT water:
        assertEquals(1, matIx[cell], "cell reset to incumbent species (air)");
        assertEquals(1.2f, mass[cell], "cell reset to stored incumbent mass");
        // one injection emitted to place water:
        assertEquals(1, out.size());
        EngineInjection ej = out.get(0);
        assertEquals(0, ej.columnId());
        assertEquals(cell, ej.cellIndex());
        assertEquals(2, ej.species(), "species resolved to water's LUT index");
        assertEquals(1000f, ej.mass());
        assertEquals(290f, ej.temperature());
    }
}
