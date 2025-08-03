package net.rainbowcreation.orge.foundation.world.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.AllBlocks;

public class WorldGenInjector {
    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (world.dimension() != Level.OVERWORLD) return; // Optional: only affect overworld

            for (int y = world.getMinBuildHeight(); y < world.getMaxBuildHeight(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockPos pos = chunk.getPos().getBlockAt(x, y, z);
                        BlockState current = chunk.getBlockState(pos);

                        if (current.is(Blocks.DIRT)) {
                           // chunk.setBlockState(pos, AllBlocks.DIRT.get().defaultBlockState(), false);
                        }
                    }
                }
            }
        });
    }
}