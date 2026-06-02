package net.rainbowcreation.orge.fluid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure {@link OrgeFluidPolicy} decision object. No Minecraft types are
 * involved: the policy takes primitives/booleans so the loader-free {@code core} stays JUnit-able.
 *
 * <p>ORGE is now fully authoritative over the water/lava it simulates in ORGE-managed sections:
 * vanilla flow/spread is suppressed there <b>unconditionally</b>, including when an interacting
 * fluid (lava&harr;water) is adjacent. Vanilla solidification (obsidian / cobblestone / basalt) is
 * intentionally disabled for managed fluids &mdash; lava cools to stone thermally via ORGE's phase
 * system, and a future lava-cooling branch will reintroduce obsidian.</p>
 */
final class OrgeFluidPolicyTest {

    @AfterEach
    void reset() {
        // Keep the static seam from leaking between tests.
        OrgeFluidPolicy.setManagedSectionPredicate(null);
    }

    @Test
    void managedFluidInManagedSection_suppresses() {
        assertTrue(OrgeFluidPolicy.shouldSuppressFlow(
                /* isOrgeFluid */ true,
                /* sectionManaged */ true));
    }

    @Test
    void managedFluidSuppressedEvenWhenLavaWaterAdjacent() {
        // REVERSAL: previously the lava<->water (obsidian) adjacency PRESERVED vanilla flow. ORGE is
        // now authoritative, so a managed fluid in a managed section is suppressed regardless of any
        // interacting neighbour. The interactingFluidAdjacent input was removed entirely.
        assertTrue(OrgeFluidPolicy.shouldSuppressFlow(
                /* isOrgeFluid */ true,
                /* sectionManaged */ true));
    }

    @Test
    void unmanagedSection_doesNotSuppress() {
        assertFalse(OrgeFluidPolicy.shouldSuppressFlow(
                /* isOrgeFluid */ true,
                /* sectionManaged */ false));
    }

    @Test
    void nonOrgeFluid_doesNotSuppress() {
        assertFalse(OrgeFluidPolicy.shouldSuppressFlow(
                /* isOrgeFluid */ false,
                /* sectionManaged */ true));
    }

    // --- infinite-water (source conversion) ----------------------------------------------------

    @Test
    void infiniteWater_isDisabledGlobally() {
        // Vanilla's "two source neighbours make a third source" fabricates mass from nothing, which
        // breaks ORGE's finite-mass model. It is disabled globally for water.
        assertFalse(OrgeFluidPolicy.allowInfiniteWater());
    }

    // --- lava solidification (obsidian / cobblestone / basalt) ---------------------------------

    @Test
    void vanillaLavaSolidification_isDisabledGlobally() {
        // LiquidBlock#shouldSpreadLiquid turns lava<->water into obsidian/cobblestone and
        // lava<->blue-ice into basalt. ORGE owns lava cooling, so this is disabled globally for now
        // (placed lava stays lava); a future lava-cooling branch reintroduces it under ORGE control.
        assertFalse(OrgeFluidPolicy.allowVanillaLavaSolidification());
    }

    // --- managed-section predicate seam --------------------------------------------------------

    @Test
    void managedPredicate_defaultsToNotManaged_whenUnset() {
        OrgeFluidPolicy.setManagedSectionPredicate(null);
        assertFalse(OrgeFluidPolicy.isSectionManaged("minecraft:overworld", 0, 4, 0));
    }

    @Test
    void managedPredicate_delegatesToInstalledHook() {
        OrgeFluidPolicy.setManagedSectionPredicate(
                (dim, cx, sectionY, cz) -> "minecraft:overworld".equals(dim) && cx == 1 && cz == 2);
        assertTrue(OrgeFluidPolicy.isSectionManaged("minecraft:overworld", 1, 4, 2));
        assertFalse(OrgeFluidPolicy.isSectionManaged("minecraft:the_nether", 1, 4, 2));
        assertFalse(OrgeFluidPolicy.isSectionManaged("minecraft:overworld", 9, 4, 9));
    }
}
