package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineInjection;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** Draining an intent overrides the column cell back to its incumbent (so Java won't reseed the new
 *  species) and emits an EngineInjection that places the new species — resolved (and registered) into
 *  the batch LUT via the SpeciesResolver. Only intents whose injection is actually emitted are marked
 *  drained (cleared on success); an unresolvable species stays queued (durability). */
class InjectionDrainTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void drainOverridesIncumbentAndEmitsInjection() {
        Material air = TestMaterials.air();
        Material water = TestMaterials.water();
        // resolver mimics the batch MaterialLut (which APPENDS on first sight): void=0, air=1, water=2.
        // The point of the resolver is that the placed species is registered even if absent from the
        // live snapshot (the bug: a stomped/first-instance fluid wasn't in lut → injection dropped).
        InjectionDrain.SpeciesResolver resolver = id ->
                id.equals(water.id()) ? (char) 2 : id.equals(air.id()) ? (char) 1 : (char) 0;

        int cell = 3 + 16 * 70 + 6144 * 4;
        char[] matIx = new char[RegionMarshaller.CHUNK_N];
        float[] mass = new float[matIx.length];
        matIx[cell] = 2; mass[cell] = 0f;   // live snapshot currently shows WATER at the cell

        float storedIncumbentMass = 1.2f;
        Identifier incumbentId = air.id();

        PendingInjections.Intent intent =
                new PendingInjections.Intent(DIM, 0, 0, cell, water.id(), 1000f, 290f);

        List<EngineInjection> out = new ArrayList<>();
        List<PendingInjections.Intent> emitted = new ArrayList<>();
        InjectionDrain.applyToColumn(
                /*columnId*/ 0, matIx, mass, resolver,
                List.of(intent),
                /*incumbentSpeciesId*/ c -> incumbentId,
                /*incumbentMass*/ c -> storedIncumbentMass,
                out, emitted);

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
        // the intent is recorded as emitted (so the scheduler clears it only after a successful step):
        assertEquals(List.of(intent), emitted, "emitted intent recorded for clear-on-success");
    }

    @Test
    void unresolvableSpeciesIsLeftQueuedNotDropped() {
        // Regression for the vanish bug: when the placed species could not be resolved the drain used
        // to skip the injection BUT the caller still cleared the intent → placement silently lost.
        // Now an unresolvable species emits nothing, touches nothing, and is NOT marked emitted.
        Material water = TestMaterials.water();
        InjectionDrain.SpeciesResolver resolver = id -> (char) 0;   // nothing resolves

        int cell = 5 + 16 * 70;
        char[] matIx = new char[RegionMarshaller.CHUNK_N];
        float[] mass = new float[matIx.length];
        matIx[cell] = 7; mass[cell] = 99f;   // arbitrary pre-existing content

        PendingInjections.Intent intent =
                new PendingInjections.Intent(DIM, 0, 0, cell, water.id(), 1000f, 290f);

        List<EngineInjection> out = new ArrayList<>();
        List<PendingInjections.Intent> emitted = new ArrayList<>();
        InjectionDrain.applyToColumn(0, matIx, mass, resolver, List.of(intent),
                c -> null, c -> 0f, out, emitted);

        assertEquals(0, out.size(), "no injection when species unresolvable");
        assertEquals(7, matIx[cell], "cell untouched when species unresolvable");
        assertEquals(99f, mass[cell], "mass untouched when species unresolvable");
        assertEquals(0, emitted.size(), "intent NOT marked emitted → stays queued for retry (durability)");
    }
}
