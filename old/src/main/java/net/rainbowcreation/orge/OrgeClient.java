package net.rainbowcreation.orge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.rainbowcreation.orge.infrastructure.socket.SimServerManager;

public class OrgeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Orge.LOGGER.info("Initializing Orge Client...");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            SimServerManager.startServer();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            SimServerManager.stopServer();
        });
        Orge.LOGGER.info("{} {} client initialized!", Orge.NAME, OrgeBuildInfo.VERSION);
    }
}
