package net.rainbowcreation.orge.fluid;

import dev.architectury.injectables.annotations.ExpectPlatform;

/**
 * Suppresses vanilla liquid physics for ORGE-managed fluid blocks so ORGE is the sole
 * authority (DESIGN §10 Decision 8). Architectury exposes no common fluid-tick cancellation,
 * so each loader bridges its own hook here via {@link ExpectPlatform}.
 *
 * <p><b>Known risk / follow-up:</b> full suppression (cancelling {@code FlowingFluid#tick} /
 * {@code LiquidBlock#tick} scheduled+random ticks and neighbour-update spread) is NOT expressible
 * through the loaders' common event APIs and likely requires a per-loader <b>mixin</b> into
 * {@code net.minecraft.world.level.material.FlowingFluid#tick}. No mixin toolchain exists in this
 * repo yet. Each impl does the best event-based suppression available and leaves a
 * {@code TODO(mixin)} where an event hook is insufficient.</p>
 */
public final class VanillaFluidSuppressor {
    private VanillaFluidSuppressor() {}

    /** Install the per-loader suppression hooks. Called once from {@code Orge.init()}. */
    @ExpectPlatform
    public static void install() {
        throw new AssertionError("ExpectPlatform implementation not found");
    }
}
