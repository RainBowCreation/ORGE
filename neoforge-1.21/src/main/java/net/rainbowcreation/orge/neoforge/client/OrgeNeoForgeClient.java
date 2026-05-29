package net.rainbowcreation.orge.neoforge.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.rainbowcreation.orge.Orge;

/**
 * NeoForge client-side setup. DESIGN.md §1 keeps client/server packages split per
 * loader; the client is also a compute worker (DESIGN.md §3).
 */
@EventBusSubscriber(modid = Orge.MOD_ID, value = Dist.CLIENT)
public final class OrgeNeoForgeClient {

    private OrgeNeoForgeClient() {
    }

    // TODO(phase: networking): register the client-side worker (STEP_INPUT handler,
    //  STEP_RESULT / HEALTH senders) via a client setup event here.
}
