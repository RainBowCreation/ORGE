package net.rainbowcreation.orge.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.rainbowcreation.orge.Orge;

/**
 * Fabric client entrypoint. DESIGN.md §1 keeps client/server packages split per loader;
 * the client is also a compute worker (DESIGN.md §3), so worker-side networking will be
 * wired here in the networking phase.
 */
public final class OrgeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // TODO(phase: networking): register the client-side worker (STEP_INPUT handler,
        //  STEP_RESULT / HEALTH senders).
        Orge.LOGGER.debug("ORGE Fabric client init.");
    }
}
