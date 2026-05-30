package net.rainbowcreation.orge.block;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.rainbowcreation.orge.Orge;

/**
 * ORGE's block registrations (common Architectury {@link DeferredRegister}, both loaders).
 *
 * <p>Core registers a new block ONLY for a concept vanilla lacks (DESIGN §7): {@code orge:steam},
 * the inert gas marker produced when water boils. It carries NO simulation data — temperature
 * lives in the per-cell {@link net.rainbowcreation.orge.section.SectionData} arrays — and has no
 * collision, no ticking, no {@code BlockItem}, so it stays a cheap 1 Hz-driven marker. Vanilla
 * phase targets (ice, stone) are overrides, never re-created here.</p>
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Orge.MOD_ID, Registries.BLOCK);

    private static final ResourceKey<Block> STEAM_KEY =
            ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Orge.MOD_ID, "steam"));

    /** Inert gas: air-like (no collision/occlusion, replaceable, instabreak, no loot, no item). */
    public static final RegistrySupplier<Block> STEAM = BLOCKS.register("steam", () -> new Block(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NONE)
                    .noCollision()
                    .noOcclusion()
                    .replaceable()
                    .instabreak()
                    .noLootTable()
                    .sound(SoundType.EMPTY)
                    .pushReaction(PushReaction.DESTROY)
                    .setId(STEAM_KEY)));   // 1.21.11 requires the block id be set on Properties

    private ModBlocks() {}

    /** Flush the deferred registrations into the live registries (call once from Orge.init). */
    public static void register() {
        BLOCKS.register();
    }
}
