package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineInjection;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.MaterialPalette;
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
    void removalIntentStompsCellToVacuumAndIsMarkedEmittedWithNoEngineInjection() {
        // Pre-fill the cell with water (non-vacuum) so we can observe the stomp-to-vacuum.
        Material water = TestMaterials.water();
        // The resolver is present but removal must NOT invoke resolver (vacuum index = 0 would be skipped).
        InjectionDrain.SpeciesResolver resolver = id ->
                id.equals(water.id()) ? (char) 2 : (char) 0;

        int cell = 7 + 16 * 65 + 6144 * 2;
        char[] matIx = new char[RegionMarshaller.CHUNK_N];
        float[] mass = new float[matIx.length];
        matIx[cell] = 2;       // water index — must be stomped to 0
        mass[cell] = 1000f;    // water mass — must be stomped to 0

        // Build a removal intent directly via the 8-arg canonical constructor.
        PendingInjections.Intent removalIntent =
                new PendingInjections.Intent(DIM, 0, 0, cell,
                        MaterialPalette.VACUUM_ID, 0f, 0f, true);

        List<EngineInjection> out = new ArrayList<>();
        List<PendingInjections.Intent> emitted = new ArrayList<>();
        InjectionDrain.applyToColumn(
                /*columnId*/ 0, matIx, mass, resolver,
                List.of(removalIntent),
                c -> water.id(),
                c -> 1000f,
                out, emitted);

        // Cell stomped to index-0 vacuum sentinel.
        assertEquals(0, matIx[cell], "removal stomps matIx to vacuum (0)");
        assertEquals(0f, mass[cell], "removal stomps mass to 0");
        // No EngineInjection emitted — removal is a stomp-only, no engine-side injection needed.
        assertEquals(0, out.size(), "no EngineInjection emitted for removal");
        // Intent recorded as emitted so the scheduler clears it after a successful write-back.
        assertEquals(1, emitted.size(), "removal intent marked emitted (clear-on-success)");
        assertTrue(emitted.contains(removalIntent), "emitted set contains the removal intent");
    }

    @Test
    void enqueueRemovalProducesRemovalFlaggedIntent() {
        // Smoke-test the PendingInjections helper so G2 can rely on it.
        PendingInjections pi = new PendingInjections();
        int cell = 4 + 16 * 10 + 6144 * 3;
        pi.enqueueRemoval(DIM, 0, 0, cell);

        List<PendingInjections.Intent> col = pi.peekColumn(DIM, 0, 0);
        assertEquals(1, col.size(), "one removal intent queued");
        PendingInjections.Intent intent = col.get(0);
        assertTrue(intent.removal(), "intent has removal=true");
        assertEquals(cell, intent.cell(), "intent targets correct cell");
        assertEquals(MaterialPalette.VACUUM_ID, intent.species(), "species is orge:vacuum");
        assertEquals(0f, intent.mass(), "mass is 0");
    }

    /**
     * Same-window break+replace-SAME-solid regression (the flow-through-a-phantom-hole bug). A capped
     * well [water, solid, air]: in ONE scheduler window the middle solid is BROKEN (queues a removal)
     * then RE-PLACED with the same solid. The capture must read {@code hasPendingRemoval} (true) and
     * force-capture the re-place so the queue ends with a PLACEMENT, not the lone removal. The drain then
     * stomps the middle cell back to its solid incumbent and emits a solid injection — it does NOT stomp
     * the cell to vacuum. (The buggy path left only the removal → middle stomped to vacuum → the left
     * water flooded through it next step.)
     */
    @Test
    void sameWindowBreakThenReplaceSameSolidDrainsAsPlacementNotVacuumStomp() {
        Material stone = TestMaterials.stone();
        // Batch LUT: void=0, stone=3 (resolver appends-on-sight, mirroring MaterialLut).
        InjectionDrain.SpeciesResolver resolver = id -> id.equals(stone.id()) ? (char) 3 : (char) 0;

        int middle = 5 + 16 * 70 + 6144 * 4;

        // 1) Same-window BREAK queues a removal at the middle cell.
        PendingInjections pi = new PendingInjections();
        pi.enqueueRemoval(DIM, 0, 0, middle);

        // 2) Same-window PLACE of the SAME solid. Production reads hasPendingRemoval and passes it through;
        //    the recorded incumbent still names the (stale) broken solid, which would normally be a
        //    self-write no-op — the pending removal is exactly what forces the capture.
        boolean pendingRemoval = pi.hasPendingRemoval(DIM, 0, 0, middle);
        assertTrue(pendingRemoval, "the same-window break left a pending removal at the middle cell");
        PlacementCapture.capture(pi, DIM, 0, 0, middle, stone, stone, 295f, pendingRemoval);

        List<PendingInjections.Intent> queued = pi.peekColumn(DIM, 0, 0);
        assertEquals(1, queued.size(), "the re-place supersedes the removal");
        assertTrue(!queued.get(0).removal(), "queued intent is the placement, not the break removal");

        // 3) Drain the (now-placement) queue: middle resets to its solid incumbent + a solid injection
        //    is emitted — NOT a vacuum stomp.
        char[] matIx = new char[RegionMarshaller.CHUNK_N];
        float[] mass = new float[matIx.length];
        matIx[middle] = 3; mass[middle] = 2500f;   // live snapshot shows the solid currently there
        float storedSolidMass = 2500f;

        List<EngineInjection> out = new ArrayList<>();
        List<PendingInjections.Intent> emitted = new ArrayList<>();
        InjectionDrain.applyToColumn(0, matIx, mass, resolver, queued,
                c -> stone.id(), c -> storedSolidMass, out, emitted);

        assertEquals(3, matIx[middle], "middle stays its SOLID incumbent (NOT stomped to vacuum 0)");
        assertEquals(2500f, mass[middle], "middle keeps solid incumbent mass (no movable-water residue)");
        assertEquals(1, out.size(), "a solid placement injection is emitted (engine re-asserts the solid)");
        assertEquals(3, out.get(0).species(), "injection places the solid species");
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
