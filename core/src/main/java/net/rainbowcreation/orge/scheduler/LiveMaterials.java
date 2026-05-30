package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialBindings;

/**
 * Live (server-thread) helpers shared by the Minecraft-coupled scheduler/phase adapters:
 * the block→{@link Material} lookup (via the live block-tag bridge) and chunk/section/cell
 * access that never forces generation. Extracted from {@link MinecraftThermalWorld} so
 * {@code MinecraftPhaseChanger} reuses the exact same logic.
 */
public final class LiveMaterials {

    private LiveMaterials() {}

    /** Live tag-membership bridge — §6's {@link MaterialBindings.TagMembership} consumer. */
    public static final MaterialBindings.TagMembership LIVE_TAGS = (tagId, blockId) -> {
        // getValue returns the default (air) for an unregistered id rather than null; safe
        // because callers only pass ids from BuiltInRegistries.BLOCK.getKey(block).
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
        return BuiltInRegistries.BLOCK.wrapAsHolder(BuiltInRegistries.BLOCK.getValue(blockId)).is(tag);
    };

    /** The {@link Material} bound to {@code block} in the given active materials state. */
    public static Material materialFor(Block block, ActiveMaterials.State mats) {
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        Identifier matId = mats.bindings().materialFor(blockId, LIVE_TAGS);
        return mats.registry().getOrFallback(matId);
    }

    /** A loaded chunk, or null if not currently loaded (never forces generation). */
    public static LevelChunk loadedChunk(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz);
    }

    /** The chunk's section at vanilla sectionY, or null if out of the chunk's Y range. */
    public static LevelChunkSection sectionOrNull(LevelChunk chunk, int sectionY) {
        int idx = chunk.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= chunk.getSectionsCount()) {
            return null;
        }
        return chunk.getSection(idx);
    }

    /** The block at section-local cell index i (x-fastest, x+16y+256z); 0..15 per axis. */
    public static Block blockAt(LevelChunkSection section, int i) {
        int x = i & 15;
        int y = (i >> 4) & 15;
        int z = (i >> 8) & 15;
        return section.getBlockState(x, y, z).getBlock();
    }
}
