package net.rainbowcreation.orge.fluid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure {@link OrgeFluidPolicy} decision object. No Minecraft types are
 * involved: the policy takes primitives/booleans so the loader-free {@code core} stays JUnit-able.
 *
 * <p>The central safety property under test is that the lava&harr;water solidification path
 * (obsidian / cobblestone / basalt &mdash; Nether-portal-critical) is NEVER suppressed: whenever an
 * interacting fluid is adjacent, the policy returns {@code false} so vanilla flow/spread keeps
 * running and the {@code LiquidBlock} placement/neighbour-change that produces obsidian stays
 * reachable.</p>
 */
final class OrgeFluidPolicyTest {

    @AfterEach
    void reset() {
        // Keep the static seam from leaking between tests.
        OrgeFluidPolicy.setManagedSectionPredicate(null);
    }

    @Test
    void managedWaterNoInteraction_suppresses() {
        assertTrue(OrgeFluidPolicy.shouldSuppressFlow(
                /* isOrgeFluid */ true,
                /* sectionManaged */ true,
                /* interactingFluidAdjacent */ false));
    }

    @Test
    void managedLavaAdjacentWater_doesNotSuppress_obsidianPreserved() {
        // Lava with an adjacent interacting fluid (water) MUST let vanilla run, or obsidian/
        // cobblestone/basalt would never form.
        assertFalse(OrgeFluidPolicy.shouldSuppressFlow(
                /* isOrgeFluid */ true,
                /* sectionManaged */ true,
                /* interactingFluidAdjacent */ true));
    }

    @Test
    void unmanagedSection_doesNotSuppress() {
        assertFalse(OrgeFluidPolicy.shouldSuppressFlow(
                /* isOrgeFluid */ true,
                /* sectionManaged */ false,
                /* interactingFluidAdjacent */ false));
    }

    @Test
    void nonOrgeFluid_doesNotSuppress() {
        assertFalse(OrgeFluidPolicy.shouldSuppressFlow(
                /* isOrgeFluid */ false,
                /* sectionManaged */ true,
                /* interactingFluidAdjacent */ false));
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
