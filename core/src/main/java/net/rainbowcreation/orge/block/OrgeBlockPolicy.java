package net.rainbowcreation.orge.block;

/**
 * Pure, loader-free decision object for vanilla-block behaviour suppression, mirroring
 * {@link net.rainbowcreation.orge.fluid.OrgeFluidPolicy} (the per-loader mixins stay thin
 * fact-gatherers; all decisions live here, headlessly testable, no Minecraft types).
 */
public final class OrgeBlockPolicy {

    private OrgeBlockPolicy() {}

    /**
     * Whether vanilla gravity blocks ({@code FallingBlock} subclasses: sand, gravel, concrete
     * powder, suspicious sand/gravel, anvils, the dragon egg, &hellip;) are allowed to fall.
     * Always {@code false}: the per-loader mixin into {@code FallingBlock#tick} cancels the
     * scheduled fall-check tick globally, so no {@code FallingBlockEntity} is ever spawned and
     * unsupported gravity blocks simply stay in place. ORGE will own granular-material collapse
     * (mass/slope driven) in its own engine; a future branch flips this back on under ORGE control.
     */
    public static boolean allowVanillaGravityBlocks() {
        return false;
    }
}
