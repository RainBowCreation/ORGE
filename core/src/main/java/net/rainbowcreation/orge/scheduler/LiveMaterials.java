package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.material.BlockMaterialRule;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;

/**
 * Live (server-thread) helpers shared by the Minecraft-coupled scheduler/phase adapters:
 * the block→{@link Material} FIRST-TOUCH lookup and chunk/section/cell access that never
 * forces generation. Extracted from {@link MinecraftThermalWorld} so
 * {@code MinecraftPhaseChanger} reuses the exact same logic.
 */
public final class LiveMaterials {

    private LiveMaterials() {}

    /** The {@link Material} a block FIRST-TOUCHES to (spec Part 1). Blockstate is irrelevant. */
    public static Material materialFor(Block block, MaterialRegistry registry) {
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        return BlockMaterialRule.firstTouchMaterial(blockId, registry);
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

    /** The {@link BlockState} at section-local cell index i (x-fastest, x+16y+256z). */
    public static BlockState blockStateAt(LevelChunkSection section, int i) {
        int x = i & 15;
        int y = (i >> 4) & 15;
        int z = (i >> 8) & 15;
        return section.getBlockState(x, y, z);
    }
}
