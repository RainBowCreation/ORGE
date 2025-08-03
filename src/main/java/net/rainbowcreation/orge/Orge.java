package net.rainbowcreation.orge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;

import com.tterrag.registrate.Registrate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import net.rainbowcreation.orge.foundation.world.worldgen.WorldGenInjector;
import net.rainbowcreation.orge.infrastructure.socket.SimConnectionManager;
import net.rainbowcreation.orge.infrastructure.socket.SimServerManager;
import net.rainbowcreation.orge.test.Test;
import org.slf4j.Logger;

import net.fabricmc.api.ModInitializer;

public class Orge implements ModInitializer {
    public static final String ID = "orge";
    public static final String NAME = "Orge";

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Registrate REGISTRATE = Registrate.create(ID);


    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Orge...");
        AllBlocks.register();
        AllItems.register();
        AllBlockEntities.register();

        try {
            //Test.main(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            SimServerManager.startServer(server);

            SimConnectionManager.setServer(server);

            while (!SimConnectionManager.isConnected()) {
                SimConnectionManager.connect();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SimConnectionManager.disconnect();

            SimServerManager.stopServer(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SimConnectionManager.processQueue();
        });

        REGISTRATE.register();
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
}
