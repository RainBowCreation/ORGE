package net.rainbowcreation.orge;

import java.io.InputStream;
import java.util.Properties;

/**
 * Build stamp, read from the {@code /orge-build.properties} resource that {@code :core}'s
 * {@code generateBuildInfo} Gradle task writes at build time. Logged once by {@link Orge#init()} so a
 * running server proves <b>which build it is</b> — the quickest way to tell a stale jar from a fresh
 * one (the declared {@link #VERSION} alone does not change between rebuilds; the SHAs and build time
 * do). {@link #ENGINE_SHA} is the {@code ORGE-ENGINE} gitlink the bundled {@code liborge.so} was built
 * from. All fields fall back to {@code "unknown"}/{@code "dev"} when the resource is absent (e.g. a
 * raw classpath run before a build stamp exists).
 */
public final class BuildInfo {
    private BuildInfo() {}

    public static final String VERSION;
    public static final String MC;
    public static final String PARENT_SHA;
    public static final String ENGINE_SHA;
    public static final String BUILD_TIME;

    static {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/orge-build.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (Exception ignored) {
            // fall through to defaults below
        }
        VERSION = p.getProperty("version", "dev");
        MC = p.getProperty("mc", "?");
        PARENT_SHA = p.getProperty("parentSha", "unknown");
        ENGINE_SHA = p.getProperty("engineSha", "unknown");
        BUILD_TIME = p.getProperty("buildTime", "unknown");
    }

    /** One-line human-readable build identity for the startup banner. */
    public static String banner() {
        return "ORGE build v" + VERSION + " (mc " + MC + ") | parent " + PARENT_SHA
                + " | engine " + ENGINE_SHA + " | built " + BUILD_TIME;
    }
}
