package net.rainbowcreation.orge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ORGE — Overhauled Realistic General Elements.
 *
 * <p>Shared, loader-agnostic entry point. Both the Fabric and NeoForge entrypoints
 * call {@link #init()} exactly once. See {@code DESIGN.md} for the authoritative
 * architecture; this class is intentionally thin in the Phase 1 skeleton.</p>
 */
public final class Orge {

    public static final String MOD_ID = "orge";

    public static final Logger LOGGER = LoggerFactory.getLogger("ORGE");

    private static boolean initialized = false;

    private Orge() {
    }

    /** Wire up the loader-agnostic subsystems. Idempotent. */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        LOGGER.info("ORGE v2 thermal core initializing (Phase 1 skeleton).");

        // DESIGN.md §6  — material model: register the built-in materials + JSON loader.
        // DESIGN.md §5  — section store: hook chunk load/unload to load/save world/orge/.
        // DESIGN.md §8  — scheduler: register the per-tick server scheduler.
        // DESIGN.md §2  — engine: bind liborge via Panama FFI (deferred; stub for now).
        // Each of the above is a stubbed subsystem under this package; later phases
        // fill them in and wire them here.
    }
}
