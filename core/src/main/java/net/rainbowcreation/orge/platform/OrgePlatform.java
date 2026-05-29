package net.rainbowcreation.orge.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;

import java.nio.file.Path;

/**
 * Loader-specific services, resolved at compile time by Architectury's
 * {@link ExpectPlatform} mechanism. Each method must have a matching
 * {@code OrgePlatformImpl} in both the fabric and neoforge subprojects.
 */
public final class OrgePlatform {

    private OrgePlatform() {
    }

    /** Config directory for this game instance (e.g. {@code .minecraft/config}). */
    @ExpectPlatform
    public static Path configDir() {
        throw new AssertionError("ExpectPlatform implementation not found");
    }

    /** Whether the given mod id is present on this runtime. */
    @ExpectPlatform
    public static boolean isModLoaded(String modId) {
        throw new AssertionError("ExpectPlatform implementation not found");
    }

    /** Whether this is the physical client distribution (has rendering). */
    @ExpectPlatform
    public static boolean isPhysicalClient() {
        throw new AssertionError("ExpectPlatform implementation not found");
    }
}
