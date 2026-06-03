package net.rainbowcreation.orge.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the engine implementation at runtime: {@link NativeEngine} when the
 * liborge native loads for this platform, otherwise the no-op {@link StubEngine}
 * (DESIGN.md §2). Keeps {@code :core} runnable where no native is bundled.
 *
 * <p>Emits one unambiguous startup line either way so a server log makes clear
 * whether real physics is running (NativeEngine) or the mod is inert (StubEngine).</p>
 */
public final class EngineFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("ORGE");

    private static volatile OrgeEngine instance;

    private EngineFactory() {}

    /** The process-wide shared engine, built lazily on first use (double-checked locking). */
    public static OrgeEngine instance() {
        OrgeEngine local = instance;
        if (local == null) {
            synchronized (EngineFactory.class) {
                local = instance;
                if (local == null) {
                    local = build();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static OrgeEngine create() {
        return instance();
    }

    private static OrgeEngine build() {
        try {
            NativeLoader.load();
            LOGGER.info("[ORGE] engine = NativeEngine (native liborge loaded from {}) -- "
                    + "full physics ACTIVE: conduction + advection + phase change.",
                    NativeLoader.resolvedResource());
            return new NativeEngine();
        } catch (Throwable t) {
            LOGGER.warn("[ORGE] engine = StubEngine (NO-OP) -- native liborge unavailable for this platform "
                    + "(tried {}). NO physics will run: fluids will not move and temperatures will not change. "
                    + "Reason: {}", NativeLoader.resolvedResource(), t.toString());
            return new StubEngine();
        }
    }
}
