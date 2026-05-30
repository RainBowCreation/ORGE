package net.rainbowcreation.orge.fluid;

/**
 * Pure, loader-free decision object for vanilla-fluid suppression (DESIGN &sect;10 Decision 8).
 *
 * <p>ORGE wants to be the sole authority over the water/lava it simulates, so the per-loader mixin
 * into {@code net.minecraft.world.level.material.FlowingFluid#tick} asks this policy whether to
 * cancel the scheduled flow/spread tick. All decision logic lives here (the mixin stays a thin
 * fact-gatherer) so it is unit-testable headlessly &mdash; no Minecraft types appear in this class.</p>
 *
 * <p><b>Safety invariant (obsidian preserved):</b> the policy returns {@code true} (suppress) ONLY
 * when the fluid is ORGE-managed water/lava, its section is an ORGE-managed/loaded section, AND no
 * adjacent interacting fluid (lava&harr;water) is present. Whenever lava and water are adjacent the
 * policy returns {@code false}, so vanilla {@code tick} keeps running and the
 * {@code LiquidBlock} placement / neighbour-change that drives
 * {@code FluidInteractionRegistry} &rarr; obsidian / cobblestone / basalt stays reachable
 * (Nether-portal-critical; spec "Vanilla thermal interactions", Decision 2026-05-30 = PRESERVE).
 * Unmanaged regions and non-fluids are also never suppressed &mdash; the safe default is to let
 * vanilla run.</p>
 */
public final class OrgeFluidPolicy {

    private OrgeFluidPolicy() {}

    /**
     * Predicate seam asking whether a subchunk is an ORGE-managed/loaded section. Implemented MC-free
     * (dimension key as a string, plus chunk-section coordinates) so the policy stays headlessly
     * testable. ORGE installs the real implementation at init from {@code SECTION_STORES}; until then
     * the seam is unset and {@link #isSectionManaged} reports "not managed" (so the policy is inert).
     */
    @FunctionalInterface
    public interface ManagedSectionPredicate {
        /**
         * @param dimensionKey vanilla dimension identifier, e.g. {@code "minecraft:overworld"}
         * @param chunkX       chunk X (block {@code >> 4})
         * @param sectionY     subchunk index (block-Y section, e.g. {@code Math.floorDiv(blockY, 16)})
         * @param chunkZ       chunk Z (block {@code >> 4})
         * @return {@code true} iff a loaded ORGE section exists for that subchunk
         */
        boolean isManaged(String dimensionKey, int chunkX, int sectionY, int chunkZ);
    }

    /** {@code null} until ORGE installs the real hook; see {@link #setManagedSectionPredicate}. */
    private static volatile ManagedSectionPredicate managedSectionPredicate;

    /**
     * Installs (or clears, with {@code null}) the managed-section predicate. Called once from
     * {@code VanillaFluidSuppressor.install()} wiring the real {@code SECTION_STORES} lookup.
     */
    public static void setManagedSectionPredicate(ManagedSectionPredicate predicate) {
        managedSectionPredicate = predicate;
    }

    /**
     * Whether the given subchunk is an ORGE-managed/loaded section. Defaults to {@code false}
     * (nothing managed &rarr; nothing suppressed) when no predicate is installed.
     */
    public static boolean isSectionManaged(String dimensionKey, int chunkX, int sectionY, int chunkZ) {
        ManagedSectionPredicate p = managedSectionPredicate;
        return p != null && p.isManaged(dimensionKey, chunkX, sectionY, chunkZ);
    }

    /**
     * The core decision: suppress vanilla flow/spread for this fluid tick?
     *
     * @param isOrgeFluid              the ticking fluid is ORGE-managed water or lava
     * @param sectionManaged           the tick position's subchunk is an ORGE-managed/loaded section
     * @param interactingFluidAdjacent a cardinal neighbour holds the interacting fluid (lava&harr;water)
     *                                 that can solidify &mdash; the obsidian/cobblestone/basalt path
     * @return {@code true} to cancel the vanilla tick; {@code false} to let vanilla run (the safe
     *         default that preserves obsidian and never freezes unmanaged regions)
     */
    public static boolean shouldSuppressFlow(boolean isOrgeFluid,
                                             boolean sectionManaged,
                                             boolean interactingFluidAdjacent) {
        if (!isOrgeFluid) {
            return false; // not a fluid ORGE simulates — vanilla owns it
        }
        if (!sectionManaged) {
            return false; // outside an ORGE-managed loaded section — do not globally freeze the world
        }
        if (interactingFluidAdjacent) {
            return false; // PRESERVE obsidian/cobblestone/basalt: let vanilla spread/solidify run
        }
        return true; // ORGE-managed fluid in a managed section with no solidifying neighbour: ORGE owns it
    }
}
