package net.rainbowcreation.orge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import com.tterrag.registrate.Registrate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.rainbowcreation.orge.foundation.world.worldgen.WorldGenInjector;
import net.rainbowcreation.orge.infrastructure.block.BlockRegistry;
import net.rainbowcreation.orge.infrastructure.socket.SimConnectionManager;
import net.rainbowcreation.orge.util.GraphExporter;
import org.slf4j.Logger;

import net.fabricmc.api.ModInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class Orge implements ModInitializer {
    public static final String ID = "orge";
    public static final String NAME = "Orge";

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Registrate REGISTRATE = Registrate.create(ID);

    public static boolean prt = false;

    public static final boolean RADIUS_MODE = true;
    public static final int SIMULATE_MAX_RADIUS = 64;
    public static final double SIMULATE_MAX_RADIUS_SQUARE = SIMULATE_MAX_RADIUS * SIMULATE_MAX_RADIUS;


    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Orge...");

        AllBlocks.register();
        AllItems.register();
        AllBlockEntities.register();
        REGISTRATE.register();

        try {
            //Test.main(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            //SimServerManager.startServer();
            SimConnectionManager.setServer(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            //SimServerManager.stopServer();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SimConnectionManager.connect();
            BlockRegistry.send();
            this.generateGraph(server);
            prt = false;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SimConnectionManager.processQueue();
        });

        WorldGenInjector.init();
        LOGGER.info("{} {} initialized!", NAME, OrgeBuildInfo.VERSION);
    }

    public static ResourceLocation asResource(String path) {
        return new ResourceLocation(ID, path);
    }

    public static Registrate registrate() {
        if (!STACK_WALKER.getCallerClass().getPackageName().startsWith("net.rainbowcreation.orge"))
            throw new UnsupportedOperationException("Other mods are not permitted to use orge's registrate instance.");
        return REGISTRATE;
    }


    private void generateGraph(MinecraftServer server) {
        try {
            Path configDir = Paths.get("config", "block-recipe-graph");
            Files.createDirectories(configDir);

            Path registryPath = Paths.get("config", "registry.json");
            JsonObject registryJson;
            if (Files.exists(registryPath)) {
                registryJson = GSON.fromJson(Files.readString(registryPath), JsonObject.class);
            } else {
                registryJson = GraphExporter.generateRegistryJson();
                Files.createDirectories(registryPath.getParent());
                Files.writeString(registryPath, GSON.toJson(registryJson));
            }

            JsonObject graph = GraphExporter.buildGraphFromRecipes(server, registryJson);

            Path graphPath = configDir.resolve("graph.json");
            Files.writeString(graphPath, GSON.toJson(graph));

            // Extract the block IDs from the generated graph
            Set<String> blockIds = new HashSet<>();
            graph.getAsJsonArray("nodes").forEach(node -> {
                JsonObject nodeObj = node.getAsJsonObject();
                blockIds.add(nodeObj.get("id").getAsString());
            });

            // Copy the necessary item textures
            GraphExporter.copyItemTextures(blockIds, configDir);

            Files.writeString(configDir.resolve("graph.html"), GraphExporter.GRAPH_HTML);
            Files.writeString(configDir.resolve("graph.js"), GraphExporter.GRAPH_JS);

            LOGGER.info("Exported block recipe graph to: " + configDir.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("Failed to export block recipe graph: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
