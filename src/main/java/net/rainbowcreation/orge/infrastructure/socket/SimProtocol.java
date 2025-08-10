package net.rainbowcreation.orge.infrastructure.socket;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.rainbowcreation.orge.Orge;
import net.rainbowcreation.orge.foundation.world.chunk.ChunkSectionSerializer;
import net.rainbowcreation.orge.infrastructure.block.BlockRegistry;

import java.io.IOException;

public class SimProtocol {
    public static JsonObject createMessage(ServerLevel world, String type, String action, String key, Object value, double x , double y, double z) {
        JsonObject message = new JsonObject();

        if (world == null) {
            message.addProperty("world", "");
        }
        else {
            message.addProperty("world", world.dimension().location().toString());
        }
        message.addProperty("type",  type);
        message.addProperty("action", action);
        message.addProperty("key",  key);

        message.addProperty("value", Orge.GSON.toJsonTree(value).toString());

        JsonObject location = new JsonObject();
        location.addProperty("x", x);
        location.addProperty("y", y);
        location.addProperty("z", z);

        message.add("location", location);
        return message;
    }

    private static byte[] getChunkByte(LevelChunk chunk) {
        byte[] chunkByte;
        try {
            chunkByte = ChunkSectionSerializer.serializeChunkSections(chunk);
        }
        catch (IOException e) {
            Orge.LOGGER.error("Failed to serialize chunk: " + e.getMessage());
            return null;
        }
        return chunkByte;
    }

    private static byte[] getChunkByte(LevelChunk chunk, double x, double y, double z) {
        byte[] chunkByte;
        try {
            chunkByte = ChunkSectionSerializer.serializeChunkSections(chunk, x, y, z);
        }
        catch (IOException e) {
            Orge.LOGGER.error("Failed to serialize chunk: " + e.getMessage());
            return null;
        }
        return chunkByte;
    }

    public static JsonObject loadChunkMessage(ServerLevel world, int x, int z) {
        byte[] chunkByte = getChunkByte(world.getChunk(x, z));
        if (chunkByte == null)
            return null;

        return createMessage(world, "chunk", "load", "", chunkByte, x, 0, z);
    }

    public static JsonObject loadChunkMessage(ServerLevel world, LevelChunk chunk) {
        byte[] chunkByte = getChunkByte(chunk);
        if (chunkByte == null)
            return null;

        ChunkPos pos = chunk.getPos();
        return createMessage(world, "chunk", "load", "", chunkByte, pos.x, 0, pos.z);
    }

    public static JsonObject loadChunkMessage(ServerLevel world, int x, int z, ServerPlayer player) {
        byte[] chunkByte = getChunkByte(world.getChunk(x, z), player.getX(), player.getY(), player.getZ());
        if (chunkByte == null)
            return null;

        return createMessage(world, "chunk", "load", "", chunkByte, x, 0, z);
    }

    public static JsonObject loadChunkMessage(ServerLevel world, LevelChunk chunk, ServerPlayer player) {
        byte[] chunkByte = getChunkByte(chunk, player.getX(), player.getY(), player.getZ());
        if (chunkByte == null)
            return null;

        ChunkPos pos = chunk.getPos();
        return createMessage(world, "chunk", "load", "", chunkByte, pos.x, 0, pos.z);
    }

    public static JsonObject unloadChunkMessage(ServerLevel world, double x, double z) {
        return createMessage(world, "chunk", "unload", "", "", x, 0, z);
    }

    public static JsonObject unloadChunkMessage(ServerLevel world, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return unloadChunkMessage(world, pos.x, pos.z);
    }

    public static JsonObject unloadChunkMessage(ServerLevel world, double x, double z, ServerPlayer player) {
        JsonObject js = new JsonObject();
        js.addProperty("x", player.getX());
        js.addProperty("y", player.getY());
        js.addProperty("z", player.getZ());
        return createMessage(world, "chunk", "unload", "player", js, x, 0, z);
    }

    public static JsonObject unloadChunkMessage(ServerLevel world, LevelChunk chunk, ServerPlayer player) {
        ChunkPos pos = chunk.getPos();
        return unloadChunkMessage(world, pos.x, pos.z, player);
    }

    public static JsonObject registryMessage() {
        return createMessage(null, "registry", "load", "", BlockRegistry.get()/*getGlobalBlockStateRegistry()*/, 0, 0, 0);
    }
}
