package net.rainbowcreation.orge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import net.fabricmc.api.ModInitializer;

public class Orge implements ModInitializer {
    public static final String ID = "orge";
    public static final String NAME = "Orge";

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Orge...");
        LOGGER.info("{} {} initialized!", NAME, OrgeBuildInfo.VERSION);
    }
}
