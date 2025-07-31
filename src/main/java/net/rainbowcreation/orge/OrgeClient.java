package net.rainbowcreation.orge;

import net.fabricmc.api.ClientModInitializer;

public class OrgeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Orge.LOGGER.info("Initializing Orge Client...");
        Orge.LOGGER.info("{} {} client initialized!", Orge.NAME, OrgeBuildInfo.VERSION);
    }
}
