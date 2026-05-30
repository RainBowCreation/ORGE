package net.rainbowcreation.orge.fluid.fabric;

import net.rainbowcreation.orge.fluid.VanillaFluidSuppressor;

/**
 * Fabric implementation of {@link VanillaFluidSuppressor} (matched by {@code <name>Impl}).
 *
 * <p><b>Status: documented no-op.</b> Fabric API (this repo's {@code fabric-lifecycle-events-v1}
 * / interaction event set) exposes <b>no</b> hook that can cancel a vanilla fluid's scheduled
 * {@code FlowingFluid#tick} / {@code LiquidBlock#tick} spread or its neighbour-update propagation —
 * there is no fluid-tick, block-update, or can-flow event to veto. The idiomatic Fabric mechanism
 * for suppressing this is a Mixin, and <b>no mixin toolchain is configured in this repo yet</b>
 * (deliberately out of scope for this slice). So this install() does nothing today; ORGE-managed
 * water/lava are still flowed by vanilla until the mixin lands.</p>
 *
 * <p>TODO(mixin): mixin into {@code net.minecraft.world.level.material.FlowingFluid#tick} (and the
 * {@code LiquidBlock} scheduled/random tick entry) to cancel vanilla flow/spread for ORGE-managed
 * water/lava cells. The mixin MUST be <b>flow/spread only</b> and MUST NOT touch the lava↔water
 * contact-solidification path ({@code FlowingFluid#receiveNeighborFluids} →
 * {@code FluidInteractionRegistry} → obsidian/cobblestone/basalt) — silencing that would break
 * Nether-portal obsidian (spec "Vanilla thermal interactions", Decision 2026-05-30 = PRESERVE).
 * Server-side only (guard {@code !level.isClientSide()}).</p>
 */
public final class VanillaFluidSuppressorImpl {

    private VanillaFluidSuppressorImpl() {
    }

    public static void install() {
        // No-op: see class javadoc + TODO(mixin). Fabric exposes no event that cancels vanilla
        // FlowingFluid#tick spread; full suppression requires a Mixin, which is not yet wired in
        // this repo. The obsidian/cobblestone/basalt lava↔water path must stay live, so we add no
        // event veto here that could risk it.
    }
}
