package net.rainbowcreation.orge.foundation.world.worldgen;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.rainbowcreation.orge.Orge;
import net.rainbowcreation.orge.infrastructure.socket.SimConnectionManager;
import net.rainbowcreation.orge.infrastructure.socket.SimProtocol;
import net.rainbowcreation.orge.util.MathUtil;

public class WorldGenInjector {
    private static final Gson gson = Orge.GSON;

    private static ServerPlayer getClosetPlayer(ServerLevel world, LevelChunk chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        BlockPos chunkCenter = new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);

        ServerPlayer closest = null;
        double closestSquaredDistance = Double.MAX_VALUE;

        for (ServerPlayer player : world.getPlayers(LivingEntity::isAlive)) {
            if (player.isSpectator())
                continue;

            double distanceSquared = MathUtil.getDistance(chunkCenter.getX(), chunkCenter.getZ(), player.getX(), player.getZ());

            if (distanceSquared < closestSquaredDistance) {
                closestSquaredDistance = distanceSquared;
                closest = player;
            }
        }
        return closest;
    }

    public static void init() {
        if (Orge.RADIUS_MODE) {
            ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
                if (!SimConnectionManager.isConnected()) return;
                ServerPlayer closest = getClosetPlayer(world, chunk);
                if (closest == null)
                    return;
                SimConnectionManager.sendMessage(SimProtocol.loadChunkMessage(world, chunk, closest).toString());
            });

            ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
                if (!SimConnectionManager.isConnected()) return;
                ServerPlayer closest = getClosetPlayer(world, chunk);
                if (closest == null)
                    return;
                SimConnectionManager.sendMessage(SimProtocol.unloadChunkMessage(world, chunk, closest).toString());
            });
        }
        else {
            ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
                if (!SimConnectionManager.isConnected()) return;
                SimConnectionManager.sendMessage(SimProtocol.loadChunkMessage(world, chunk).toString());
            });

            ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
                if (!SimConnectionManager.isConnected()) return;
                SimConnectionManager.sendMessage(SimProtocol.unloadChunkMessage(world, chunk).toString());
            });
        }
    }
}