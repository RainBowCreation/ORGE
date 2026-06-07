package net.rainbowcreation.orge.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import net.rainbowcreation.orge.fluid.OrgeFluidPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge mixin closing the <b>THIRD</b> vanilla/NeoForge lava-solidification path — the one the
 * 2026-06-07 in-game audit found still alive after {@link LiquidBlockMixin} (vanilla
 * {@code LiquidBlock#shouldSpreadLiquid}) and {@link LavaFluidMixin} ({@code LavaFluid#spreadTo} →
 * stone). On NeoForge 21.11, lava&harr;water → obsidian / cobblestone (and lava&harr;blue-ice →
 * basalt) is centralised in {@code net.neoforged.neoforge.fluids.FluidInteractionRegistry}: every
 * interaction is gated by the {@code public static boolean canInteract(Level, BlockPos)} check, which
 * is invoked from {@code LiquidBlock#shouldSpreadLiquid} <b>and</b> from fluid placement / neighbour
 * paths that the {@code shouldSpreadLiquid} HEAD-return does not cover. The audit repro: <i>placing</i>
 * lava beside water (or water beside lava), or the engine swap writing a lava block adjacent to water,
 * produced obsidian, while flow contact (tick-suppressed) did not.</p>
 *
 * <p>We inject at {@code HEAD} of {@code canInteract} and force {@code false} whenever
 * {@link OrgeFluidPolicy#allowVanillaLavaSolidification()} is off — so NO fluid interaction can fire,
 * from any caller. Global (un-gated by section) by design, matching {@link LiquidBlockMixin}: ORGE owns
 * all lava cooling (mass → stone by temperature via the phase system), and a future lava-cooling branch
 * flips the policy back on. {@code remap = false}: {@code FluidInteractionRegistry} is a NeoForge class,
 * not a Mojang-mapped one. Server-side effect only (block edits only run server-side).</p>
 */
@Mixin(FluidInteractionRegistry.class)
public abstract class FluidInteractionRegistryMixin {

    @Inject(method = "canInteract(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void orge$noVanillaFluidInteraction(Level level, BlockPos pos,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (!OrgeFluidPolicy.allowVanillaLavaSolidification()) {
            cir.setReturnValue(false);
        }
    }
}
