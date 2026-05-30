package net.rainbowcreation.orge.engine;

/**
 * Picks the engine implementation at runtime: {@link NativeEngine} when the
 * liborge native loads for this platform, otherwise the no-op {@link StubEngine}
 * (DESIGN.md §2). Keeps {@code :core} runnable where no native is bundled.
 */
public final class EngineFactory {

    private EngineFactory() {}

    public static OrgeEngine create() {
        try {
            NativeLoader.load();
            return new NativeEngine();
        } catch (Throwable t) {
            System.err.println("[ORGE] native engine unavailable, using StubEngine: " + t.getMessage());
            return new StubEngine();
        }
    }
}
