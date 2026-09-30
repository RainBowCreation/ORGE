package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FluidReconcileDecider} — the pure per-cell §10 reconcile decision lifted out of
 * the {@code MinecraftFluidReconciler} adapter. The adapter resolves a cell's MC-typed facts once
 * (world material, engine species, mass, render bucket, is-liquid, is-air) and this decider returns
 * the single SKIP / CLEAR / PLACE action — so the load-bearing decision is testable with no
 * {@code ServerLevel} mock. These mirror the branch order the adapter loop used to inline.
 */
class FluidReconcileDeciderTest {

    private static Identifier mc(String path) { return Identifier.fromNamespaceAndPath("minecraft", path); }
    private static Identifier orge(String path) { return Identifier.fromNamespaceAndPath("orge", path); }

    /** Movable (viscosity 0), full reference 1000 kg, repr defaults to minecraft:water. */
    private static Material water() {
        return Material.builder(orge("water"))
                .thermalConductivity(0.6f).heatCapacity(1000f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(293f).viscosity(0f)
                .representativeBlock(mc("water"))
                .build();
    }

    /** Movable (viscosity 0), tiny 1.2 kg reference, repr minecraft:air (invisible gas). */
    private static Material air() {
        return Material.builder(orge("air"))
                .thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.029f)
                .defaultMass(1.2f).defaultTemperature(293f).viscosity(0f)
                .representativeBlock(mc("air"))
                .build();
    }

    /** Not movable (viscosity defaults to +∞), repr minecraft:stone. */
    private static Material stone() {
        return Material.builder(orge("stone"))
                .thermalConductivity(2.0f).heatCapacity(800f).molarMass(0.06f)
                .defaultMass(2500f).defaultTemperature(293f)
                .representativeBlock(mc("stone"))
                .build();
    }

    @Test
    void nonFluidThatStaysNonFluidIsSkipped() {
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                stone(), stone(), 2500f, FluidReconcileLogic.REMOVE, false, false);
        assertEquals(FluidReconcileDecider.Kind.SKIP, a.kind());
    }

    @Test
    void fullWaterPlacesItsReprBlockAtLevelZero() {
        // world block already water but at a different bucket (REMOVE) -> not throttled -> PLACE.
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                water(), water(), 1000f, FluidReconcileLogic.REMOVE, true, false);
        assertEquals(FluidReconcileDecider.Kind.PLACE, a.kind());
        assertEquals(mc("water"), a.block());
        assertEquals(0, a.renderLevel());
    }

    @Test
    void sameSpeciesWithinSameBucketIsThrottled() {
        // water -> water, both full (level 0), world already shows bucket 0 -> no packet.
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                water(), water(), 1000f, 0, true, false);
        assertEquals(FluidReconcileDecider.Kind.SKIP, a.kind());
    }

    @Test
    void speciesChangeNeverThrottles_vacatedWaterBecomesAir() {
        // Regression: engine Pass A swapped air up into a stale water block. The NEW species is air
        // (full, level 0) while the world block still shows water at bucket 0 — the numeric level
        // coincides but the species changed, so the stale water MUST be overwritten (air repr block).
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                water(), air(), 1.2f, 0, true, false);
        assertEquals(FluidReconcileDecider.Kind.PLACE, a.kind());
        assertEquals(mc("air"), a.block());
    }

    @Test
    void emptyFluidCellOverALiquidBlockIsCleared() {
        // mass ~ 0 -> REMOVE; world currently holds a managed fluid -> clear it to air.
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                water(), water(), 0f, 0, true, false);
        assertEquals(FluidReconcileDecider.Kind.CLEAR, a.kind());
    }

    @Test
    void emptyFluidCellOverANonLiquidBlockIsSkipped() {
        // A solid cell the engine says became water but with ~0 mass: REMOVE + non-liquid -> nothing
        // to clear (don't touch the solid).
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                stone(), water(), 0f, FluidReconcileLogic.REMOVE, false, false);
        assertEquals(FluidReconcileDecider.Kind.SKIP, a.kind());
    }

    @Test
    void contactWhitelistDoesNotStompASolid() {
        // §7 owns water+lava->obsidian: the engine says this solid cell became water (full), but the
        // live block is neither air nor a managed fluid -> never stomp it.
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                stone(), water(), 1000f, FluidReconcileLogic.REMOVE, false, false);
        assertEquals(FluidReconcileDecider.Kind.SKIP, a.kind());
    }

    // ── engine-species paint rule (Java never decides species; it draws what the engine reports) ──

    /** Movable lava, full reference 2650 kg, repr minecraft:lava. */
    private static Material lava() {
        return Material.builder(orge("lava"))
                .thermalConductivity(1.5f).heatCapacity(1150f).molarMass(0.065f)
                .defaultMass(2650f).defaultTemperature(1400f).viscosity(500f)
                .representativeBlock(mc("lava"))
                .build();
    }

    /** Movable gas whose repr block does not exist (downgrades to air at the registry boundary). */
    private static Material steam() {
        return Material.builder(orge("steam"))
                .thermalConductivity(0.025f).heatCapacity(2080f).molarMass(0.018f)
                .defaultMass(0.6f).defaultTemperature(400f).viscosity(1.3e-5f)
                .representativeBlock(mc("steam"))
                .build();
    }

    /** Immovable catch-all for unmapped blocks (oak_log etc.). */
    private static Material genericSolid() {
        return Material.builder(orge("generic_solid"))
                .thermalConductivity(2f).heatCapacity(840f).molarMass(0.06f)
                .defaultMass(2500f).defaultTemperature(290f)
                .representativeBlock(mc("stone"))
                .build();
    }

    @Test
    void engineSolidifiedLavaIsPaintedAsStone() {
        // The engine relabeled lava -> stone (keep mass + E); the world still shows the input lava.
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                lava(), stone(), 2650f, 0, true, false, true);
        assertEquals(FluidReconcileDecider.Kind.PLACE, a.kind());
        assertEquals(mc("stone"), a.block());
    }

    @Test
    void engineMeltedStoneIsPaintedAsLavaBypassingTheWhitelist() {
        // stone -> lava: the live block is a solid, but it is the engine's own previous species.
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                stone(), lava(), 2700f, FluidReconcileLogic.REMOVE, false, false, true);
        assertEquals(FluidReconcileDecider.Kind.PLACE, a.kind());
        assertEquals(mc("lava"), a.block());
        assertEquals(0, a.renderLevel());
    }

    @Test
    void staleEngineSpeciesNeverStompsAnExternallyChangedBlock() {
        // A player placed stone after the snapshot (world no longer shows the input species): the
        // engine's stale "water" output must not overwrite it.
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                stone(), water(), 1000f, FluidReconcileLogic.REMOVE, false, false, false);
        assertEquals(FluidReconcileDecider.Kind.SKIP, a.kind());
    }

    @Test
    void boiledWaterIsPaintedWithSteamsReprBlock() {
        // water -> steam: steam's repr block downgrades to air at the adapter; the decider just asks
        // for steam's repr (the adapter's setIfChanged then no-ops once the cell shows air).
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                water(), steam(), 0.6f, 0, true, false, true);
        assertEquals(FluidReconcileDecider.Kind.PLACE, a.kind());
        assertEquals(mc("steam"), a.block());
    }

    @Test
    void unmappedBlockWithUnchangedSpeciesIsNotPainted() {
        // oak_log reads as generic_solid on both sides -> no species change -> untouched.
        FluidReconcileDecider.Action a = FluidReconcileDecider.decide(
                genericSolid(), genericSolid(), 2500f, FluidReconcileLogic.REMOVE, false, false, true);
        assertEquals(FluidReconcileDecider.Kind.SKIP, a.kind());
    }
}
