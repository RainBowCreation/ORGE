package net.rainbowcreation.orge.fluid.neoforge;

import net.rainbowcreation.orge.fluid.VanillaFluidSuppressor;

/**
 * NeoForge implementation of {@link VanillaFluidSuppressor} (matched by {@code <name>Impl}).
 *
 * <p><b>Status: documented no-op.</b> NeoForge exposes more fluid/level events than Fabric, but
 * none of them cancels the spread we need to suppress, and the one cancellable fluid event is
 * exactly the path we must <b>preserve</b>:</p>
 * <ul>
 *   <li>{@code BlockEvent.FluidPlaceBlockEvent} (cancellable) fires when lava↔water contact places
 *       <b>obsidian / cobblestone / basalt</b>. This is the Nether-portal-obsidian path and MUST
 *       keep firing (spec "Vanilla thermal interactions", Decision 2026-05-30 = PRESERVE), so we
 *       deliberately do <b>not</b> listen to or cancel it.</li>
 *   <li>{@code CreateFluidSourceEvent} only vetoes infinite-source <i>creation</i> — it does not
 *       stop a fluid from flowing/spreading, so it cannot suppress vanilla liquid physics.</li>
 *   <li>{@code BlockEvent.NeighborNotifyEvent} cancels neighbour <i>notifications</i> broadly, not
 *       the fluid's own scheduled {@code FlowingFluid#tick}; the fluid still re-schedules and
 *       spreads, and a blanket cancel would also starve unrelated block updates and could disturb
 *       the lava↔water contact path. Not a safe or sufficient suppressor.</li>
 * </ul>
 * <p>There is no NeoForge event that cancels {@code FlowingFluid#tick} / {@code LiquidBlock#tick}
 * scheduled+random ticks, so full suppression requires a Mixin, and <b>no mixin toolchain is
 * configured in this repo yet</b> (out of scope for this slice). install() therefore does nothing
 * today; ORGE-managed water/lava are still flowed by vanilla until the mixin lands.</p>
 *
 * <p>TODO(mixin): mixin into {@code net.minecraft.world.level.material.FlowingFluid#tick} (and the
 * {@code LiquidBlock} scheduled/random tick entry) to cancel vanilla flow/spread for ORGE-managed
 * water/lava cells. The mixin MUST be <b>flow/spread only</b> and MUST NOT touch the lava↔water
 * contact-solidification path ({@code FlowingFluid#receiveNeighborFluids} →
 * {@code FluidInteractionRegistry}; surfaced via {@code FluidPlaceBlockEvent}) → obsidian /
 * cobblestone / basalt. Server-side only.</p>
 */
public final class VanillaFluidSuppressorImpl {

    private VanillaFluidSuppressorImpl() {
    }

    public static void install() {
        // No-op: see class javadoc + TODO(mixin). No NeoForge event cancels vanilla FlowingFluid#tick
        // spread; the only cancellable fluid event (FluidPlaceBlockEvent) is the obsidian/cobblestone/
        // basalt path we must PRESERVE. Full suppression requires a Mixin, not yet wired in this repo,
        // so we register no listener here that could risk that contact-solidification path.
    }
}
