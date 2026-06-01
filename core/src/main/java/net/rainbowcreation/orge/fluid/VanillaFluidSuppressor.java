package net.rainbowcreation.orge.fluid;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Objects;

/**
 * Wires ORGE's vanilla-fluid suppression so ORGE is the sole authority over the water/lava it
 * simulates (DESIGN &sect;10 Decision 8).
 *
 * <p><b>The mixin now exists.</b> Each loader ships a thin mixin into
 * {@code net.minecraft.world.level.material.FlowingFluid#tick(ServerLevel, BlockPos, BlockState,
 * FluidState)} that, at {@code HEAD}, delegates to {@link OrgeFluidSuppressionBridge} &rarr;
 * {@link OrgeFluidPolicy} and {@code ci.cancel()}s the vanilla flow/spread tick for ORGE-managed
 * water/lava in ORGE-managed loaded sections. The mixin auto-applies at class-load, so there are no
 * events to register here; this class's job is to inject the <b>managed-section hook</b> the policy
 * needs.</p>
 *
 * <p>{@link #install(SectionStoreManager)} sets {@link OrgeFluidPolicy}'s managed-section predicate to
 * query the real {@link SectionStoreManager}: a subchunk is "managed" iff its dimension has a live
 * {@link SectionStore} that {@link SectionStore#hasSection holds} that section. Until this is called
 * the policy's predicate is unset and suppresses nothing, so the mixin is inert (the safe default).</p>
 *
 * <p>The previous {@code @ExpectPlatform} per-loader split (and its two no-op {@code *Impl} classes)
 * was removed: the only loader-specific part is now the mixin (applied by each loader's mixin config),
 * and the predicate wiring is pure common code over {@link SectionStoreManager}. ORGE is now
 * authoritative over managed fluids: the obsidian / cobblestone / basalt path is intentionally
 * disabled inside managed sections (no adjacent-interacting-fluid carve-out), with thermal cooling
 * to stone owning lava and a future lava-cooling branch reintroducing obsidian.</p>
 */
public final class VanillaFluidSuppressor {
    private VanillaFluidSuppressor() {}

    /**
     * Installs the managed-section predicate from the live {@link SectionStoreManager}. Called once
     * from {@code Orge.init()}.
     */
    public static void install(SectionStoreManager stores) {
        Objects.requireNonNull(stores, "stores");
        OrgeFluidPolicy.setManagedSectionPredicate((dimensionKey, chunkX, sectionY, chunkZ) -> {
            Identifier dim = Identifier.tryParse(dimensionKey);
            if (dim == null) {
                return false;
            }
            SectionStore store = stores.store(dim);
            return store != null && store.hasSection(new SubchunkKey(chunkX, sectionY, chunkZ));
        });
    }
}
