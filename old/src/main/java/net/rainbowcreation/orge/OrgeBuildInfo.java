package net.rainbowcreation.orge;

import net.minecraft.Util;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public class OrgeBuildInfo {
    public static final String VERSION = Util.make(() -> {
        ModContainer container = FabricLoader.getInstance().getModContainer(Orge.ID).orElseThrow();
        return container.getMetadata().getVersion().getFriendlyString().split("\\+")[0];
    });
}
