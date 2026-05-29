package net.rainbowcreation.orge.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.rainbowcreation.orge.Orge;

/** NeoForge entrypoint. */
@Mod(Orge.MOD_ID)
public final class OrgeNeoForge {

    public OrgeNeoForge(IEventBus modEventBus) {
        Orge.init();
        // TODO(phase: scheduler/networking): subscribe server-tick + payload handlers
        //  on the appropriate NeoForge event buses.
    }
}
