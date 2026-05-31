package net.rainbowcreation.orge.engine;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Extracts the bundled {@code liborge} native for this platform from
 * {@code /natives/<os>-<arch>/} on the classpath to a temp file and
 * {@link System#load(String)}s it (DESIGN.md §2 "Build & native integration";
 * the old SimServerManager extraction trick, minus the subprocess).
 */
public final class NativeLoader {

    private static volatile boolean loaded = false;
    /** The classpath resource path the last {@link #load()} resolved for this platform (for diagnostics). */
    private static volatile String resolvedResource = "(load not attempted)";

    private NativeLoader() {}

    /** Idempotent. Throws {@link UnsatisfiedLinkError} if no matching native is bundled. */
    public static synchronized void load() {
        if (loaded) return;
        String os = osToken(System.getProperty("os.name"));
        String arch = archToken(System.getProperty("os.arch"));
        String lib = libFileName(os);
        String resource = "/natives/" + os + "-" + arch + "/" + lib;
        resolvedResource = resource;
        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("no bundled native library at " + resource);
            }
            Path tmp = Files.createTempFile("orge-native-", suffixFor(os));
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            throw e;
        } catch (Exception e) {
            throw new UnsatisfiedLinkError("failed to load native: " + e);
        }
    }

    public static boolean isLoaded() { return loaded; }

    /** The {@code /natives/<os>-<arch>/<lib>} resource the last load attempt targeted (diagnostics). */
    public static String resolvedResource() { return resolvedResource; }

    static String osToken(String osName) {
        String s = osName.toLowerCase(Locale.ROOT);
        if (s.contains("linux")) return "linux";
        if (s.contains("win")) return "windows";
        if (s.contains("mac") || s.contains("darwin")) return "macos";
        throw new IllegalStateException("unsupported OS: " + osName);
    }

    static String archToken(String osArch) {
        String s = osArch.toLowerCase(Locale.ROOT);
        if (s.equals("amd64") || s.equals("x86_64")) return "x64";
        if (s.equals("aarch64") || s.equals("arm64")) return "arm64";
        throw new IllegalStateException("unsupported arch: " + osArch);
    }

    static String libFileName(String os) {
        return switch (os) {
            case "linux" -> "liborge.so";
            case "windows" -> "orge.dll";
            case "macos" -> "liborge.dylib";
            default -> throw new IllegalStateException("unsupported OS token: " + os);
        };
    }

    private static String suffixFor(String os) {
        return switch (os) {
            case "windows" -> ".dll";
            case "macos" -> ".dylib";
            default -> ".so";
        };
    }
}
