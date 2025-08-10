package net.rainbowcreation.orge.infrastructure.socket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.Orge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SimConnectionManager {

    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 6969;
    private static final Logger log = LoggerFactory.getLogger(SimConnectionManager.class);
    private static int MAX_MESSAGES_PER_TICK = 10;

    private static Socket socket;
    private static PrintWriter writer;
    private static BufferedReader reader;
    private static MinecraftServer mcServerInstance;

    public static final Queue<JsonObject> messageQueue = new ConcurrentLinkedQueue<>();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static Future<?> readerTask;
    private static Gson gson = Orge.GSON;
    private static boolean isConnected = false;

    public static boolean isConnected() {
        return isConnected;
    }

    public static void setServer(MinecraftServer server) {
        mcServerInstance = server;
    }

    public static void connect() {
        executor.submit(() -> {
            try {
                if (socket != null && !socket.isClosed()) {
                    disconnect();
                }
                socket = new Socket(SERVER_IP, SERVER_PORT);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new PrintWriter(socket.getOutputStream(), true);

                logMessage("§a[Sim] Connected to simulation server.");
                isConnected = true;

                readerTask = executor.submit(SimConnectionManager::listenForMessages);
            } catch (Exception e) {
                logMessage("§c[Sim] Failed to connect: " + e.getMessage());
            }
        });
    }

    public static void disconnect() {
        try {
            if (readerTask != null) {
                readerTask.cancel(true);
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
                logMessage("§c[Sim] Disconnected from server.");
                isConnected = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void listenForMessages() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                try {
                    JsonObject jsonObject = gson.fromJson(line, JsonObject.class);
                    messageQueue.add(jsonObject);
                } catch (Exception e) {
                    logMessage("§c[Sim] Failed to parse JSON: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logMessage("§c[Sim] Connection lost: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    public static void processQueue() {
        if (mcServerInstance == null) return;

        int messagesProcessed = 0;

        JsonObject jsonObject;
        while ((jsonObject = messageQueue.poll()) != null && messagesProcessed < MAX_MESSAGES_PER_TICK) {
            JsonObject finalJsonObject = jsonObject;
            mcServerInstance.execute(() -> processBlockChange(finalJsonObject));
            messagesProcessed++;
        }
    }

    public static void sendMessage(String message) {
        sendMessage(message, false);
    }

    public static void sendMessage(String message, boolean debug) {
        if (isConnected && writer != null) {
            try {
                writer.println(message);
                writer.flush();
                if (debug) {
                    logMessage("§d[Sim] Sent message: " + message);
                }
            } catch (Exception e) {
                if (debug) {
                    logMessage("§c[Sim] Failed to send message: " + e.getMessage());
                }
            }
        } else {
            if (debug) {
                logMessage("§c[Sim] Not connected to server. Failed to send message.");
            }
        }
    }

    private static void processBlockChange(JsonObject jsonObject) {
        int x = jsonObject.getAsJsonObject("location").get("x").getAsInt();
        int y = jsonObject.getAsJsonObject("location").get("y").getAsInt();
        int z = jsonObject.getAsJsonObject("location").get("z").getAsInt();
        String value = jsonObject.get("value").getAsString();
        int worldId = jsonObject.get("world").getAsInt();

        Level world = mcServerInstance.getLevel(Level.OVERWORLD);
        world = switch (worldId) {
            case 1 -> mcServerInstance.getLevel(Level.NETHER);
            case 2 -> mcServerInstance.getLevel(Level.END);
            default -> world;
        };

        BlockPos pos = new BlockPos(x, y, z);
        BlockState newState = switch (value) {
            case "liquid" -> Blocks.WATER.defaultBlockState();
            case "solid" -> Blocks.STONE.defaultBlockState();
            case "gas" -> Blocks.AIR.defaultBlockState();
            default -> null;
        };

        if (newState != null) {
            world.setBlock(pos, newState, 3);
        }

        logMessage("§b[Sim] Updated block at (" + x + ", " + y + ", " + z + ") to " + value);
    }

    private static void logMessage(String message) {
        Orge.LOGGER.info(message);
    }
}
