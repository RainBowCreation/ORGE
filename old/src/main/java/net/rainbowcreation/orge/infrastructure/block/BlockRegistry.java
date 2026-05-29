package net.rainbowcreation.orge.infrastructure.block;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.Orge;
import net.rainbowcreation.orge.infrastructure.socket.SimConnectionManager;
import net.rainbowcreation.orge.infrastructure.socket.SimProtocol;

import net.minecraft.core.Registry;
import net.rainbowcreation.orge.util.JsonHelper;

public class BlockRegistry {
    private static final Gson gson = Orge.GSON;
    public static JsonObject get() {
        JsonObject blockRegistry = new JsonObject();

        BuiltInRegistries.BLOCK.forEach(block -> {
            int id = BuiltInRegistries.BLOCK.getId(block);
            String blockName = BuiltInRegistries.BLOCK.getKey(block).toString();
            blockRegistry.addProperty(String.valueOf(id), blockName);
        });
        JsonHelper.saveJsonObjectToFile(blockRegistry, "registry.json");
        return blockRegistry;
    }

    public static JsonObject getGlobalBlockStateRegistry() {
        JsonObject blockStateRegistry = new JsonObject();

        // Use the explicit IdMapper from the Block class.
        // This is the correct type based on your compiler error.
        IdMapper<BlockState> blockStateIdMapper = Block.BLOCK_STATE_REGISTRY;

        // Iterate through all BlockStates in the IdMapper
        // Since IdMapper doesn't have a forEach method like Registry, we need to iterate differently.
        // A common pattern is to loop from 0 to the size of the IdMapper.
        for (int i = 0; i < blockStateIdMapper.size(); i++) {
            BlockState blockState = blockStateIdMapper.byId(i);
            if (blockState != null) {
                String blockStateString = blockState.toString(); // e.g., "minecraft:grass_block[snowy=false]"
                blockStateRegistry.addProperty(String.valueOf(i), blockStateString);
            }
        }

        JsonHelper.saveJsonObjectToFile(blockStateRegistry, "registry.json");
        return blockStateRegistry;
    }

    public static void send() {
        SimConnectionManager.sendMessage(SimProtocol.registryMessage().toString(), false);
    }
}
