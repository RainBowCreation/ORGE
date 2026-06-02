package net.rainbowcreation.orge.fluid;

/**
 * Pure, loader-free decision object for vanilla-fluid suppression (DESIGN &sect;10 Decision 8).
 *
 * <p>ORGE wants to be the sole authority over the water/lava it simulates, so the per-loader mixin
 * into {@code net.minecraft.world.level.material.FlowingFluid#tick} asks this policy whether to
 * cancel the scheduled flow/spread tick. All decision logic lives here (the mixin stays a thin
 * fact-gatherer) so it is unit-testable headlessly &mdash; no Minecraft types appear in this class.</p>
 *
 * <p><b>Authority invariant (obsidian REVERSED):</b> the policy returns {@code true} (suppress) for
 * ORGE-managed water/lava in an ORGE-managed/loaded section <b>unconditionally</b> &mdash; including
 * when an interacting fluid (lava&harr;water) is adjacent. ORGE is now the sole authority over the
 * fluids it simulates, so vanilla solidification (obsidian / cobblestone / basalt) is intentionally
 * disabled for managed fluids: lava cools to stone thermally via ORGE's phase system, and a future
 * lava-cooling branch will reintroduce obsidian under ORGE control. (This reverses the prior
 * 2026-05-30 PRESERVE decision; the {@code interactingFluidAdjacent} input that drove it was
 * removed.) Unmanaged regions and non-fluids are still never suppressed &mdash; the safe default is
 * to let vanilla run there.</p>
 *
 * <p><b>Infinite water disabled globally:</b> {@link #allowInfiniteWater()} returns {@code false} so
 * the per-loader mixin into {@code FlowingFluid#canConvertToSource} prevents two source neighbours
 * from fabricating a third source &mdash; that "mass from nothing" breaks ORGE's finite-mass model.
 * This is a global (not section-scoped) disable, as the user asked to remove the vanilla feature.</p>
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
     * @param isOrgeFluid    the ticking fluid is ORGE-managed water or lava
     * @param sectionManaged the tick position's subchunk is an ORGE-managed/loaded section
     * @return {@code true} to cancel the vanilla tick (ORGE-managed fluid in a managed section: ORGE
     *         owns it, including lava&harr;water contact); {@code false} to let vanilla run (the safe
     *         default for non-fluids and unmanaged regions)
     */
    public static boolean shouldSuppressFlow(boolean isOrgeFluid, boolean sectionManaged) {
        if (!isOrgeFluid) {
            return false; // not a fluid ORGE simulates — vanilla owns it
        }
        if (!sectionManaged) {
            return false; // outside an ORGE-managed loaded section — do not globally freeze the world
        }
        return true; // ORGE-managed fluid in a managed section — ORGE is authoritative (obsidian off)
    }

    /**
     * Whether vanilla "infinite water" (a flowing water cell with &ge;2 adjacent sources converting
     * itself to a source) is allowed. Always {@code false}: that conversion fabricates mass from
     * nothing and breaks ORGE's finite-mass model, so the per-loader {@code canConvertToSource} mixin
     * forces it off globally for water.
     */
    public static boolean allowInfiniteWater() {
        return false;
    }

    /**
     * Whether vanilla lava solidification on fluid contact is allowed. This covers every solidifying
     * branch inside {@code LiquidBlock#shouldSpreadLiquid}: lava&harr;water &rarr; obsidian (source) /
     * cobblestone (flowing), and lava&harr;blue-ice &rarr; basalt (the "lava-ice" interaction). Always
     * {@code false} for now: ORGE owns lava cooling, so the per-loader {@code shouldSpreadLiquid} mixin
     * forces that method onto its non-solidifying branch <b>globally</b> (placed lava stays lava, no
     * obsidian / cobblestone / basalt). This is a global (not section-scoped) disable &mdash; it also
     * closes the managed/unmanaged async window where vanilla physics could solidify a just-placed
     * lava cell before ORGE adopts it. A future lava-cooling branch flips this on under ORGE control.
     */
    public static boolean allowVanillaLavaSolidification() {
        return false;
    }
}
