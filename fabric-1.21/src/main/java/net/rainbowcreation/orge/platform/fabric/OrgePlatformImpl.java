package net.rainbowcreation.orge.platform.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.rainbowcreation.orge.platform.OrgePlatform;

import java.nio.file.Path;

/** Fabric implementation of {@link OrgePlatform} (matched by {@code <name>Impl}). */
public final class OrgePlatformImpl {

    private OrgePlatformImpl() {
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static boolean isPhysicalClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }
}
