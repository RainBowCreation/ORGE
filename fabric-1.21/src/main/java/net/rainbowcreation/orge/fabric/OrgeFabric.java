package net.rainbowcreation.orge.fabric;

import net.fabricmc.api.ModInitializer;
import net.rainbowcreation.orge.Orge;

/** Fabric common entrypoint (server + client). */
public final class OrgeFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Orge.init();
    }
}
