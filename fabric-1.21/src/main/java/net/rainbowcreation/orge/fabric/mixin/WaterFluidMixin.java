package net.rainbowcreation.orge.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import net.rainbowcreation.orge.fluid.OrgeFluidPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric mixin that disables vanilla "infinite water" globally so ORGE's finite-mass model holds.
 *
 * <p>Vanilla water turns a flowing cell into a source when it has &ge;2 adjacent sources (governed by
 * the {@code waterSourceConversion} game rule) via {@code WaterFluid#canConvertToSource}. That
 * fabricates mass from nothing, which breaks ORGE's mass conservation. We force the method to return
 * {@code false} for water so two sources can never make a third.</p>
 *
 * <p>Target verified against the 1.21.11 Mojang-mapped sources: {@code WaterFluid} overrides
 * {@code FlowingFluid#canConvertToSource(ServerLevel)} (descriptor
 * {@code (Lnet/minecraft/server/level/ServerLevel;)Z}). Scoped to {@code WaterFluid} so lava's source
 * conversion (it overrides the same method on {@code LavaFluid}) is untouched. The decision is routed
 * through the pure {@link OrgeFluidPolicy#allowInfiniteWater()} so it stays headlessly testable.
 * Global by design &mdash; the user asked to remove the vanilla feature, not gate it per section.</p>
 */
@Mixin(net.minecraft.world.level.material.WaterFluid.class)
public abstract class WaterFluidMixin {

    @Inject(method = "canConvertToSource(Lnet/minecraft/server/level/ServerLevel;)Z",
            at = @At("HEAD"), cancellable = true)
    private void orge$disableInfiniteWater(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        if (!OrgeFluidPolicy.allowInfiniteWater()) {
            cir.setReturnValue(false);
        }
    }
}
