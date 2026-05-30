package net.rainbowcreation.orge.platform.neoforge;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.rainbowcreation.orge.platform.OrgePlatform;

import java.nio.file.Path;

/** NeoForge implementation of {@link OrgePlatform} (matched by {@code <name>Impl}). */
public final class OrgePlatformImpl {

    private OrgePlatformImpl() {
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static boolean isPhysicalClient() {
        return FMLEnvironment.getDist().isClient();
    }
}
