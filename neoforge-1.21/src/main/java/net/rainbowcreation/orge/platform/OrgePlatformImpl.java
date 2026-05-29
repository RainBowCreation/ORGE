package net.rainbowcreation.orge.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

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
        return FMLEnvironment.dist.isClient();
    }
}
