package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * The active, in-game material state (DESIGN.md §6): the {@link MaterialRegistry}
 * and {@link MaterialBindings} currently in force, swapped wholesale on each
 * {@code /reload} by {@link MaterialJsonLoader}.
 *
 * <p>The references are {@code volatile} so the reload listener can publish a fresh
 * pair from the off-thread reload worker and have readers on other threads observe
 * it without further synchronization.</p>
 *
 * <h2>Atomicity</h2>
 * <p>{@link #buildState(Map, List)} constructs a fresh {@link State} from raw JSON
 * <em>without touching the active state</em>; if {@link MaterialData} rejects the
 * input it throws and the currently-active state is left untouched. Only a
 * successfully-built state is ever handed to {@link #swap(State)}. This is what
 * makes a failed reload non-corrupting.</p>
 */
public final class ActiveMaterials {

    /**
     * A published materials state. The reload listener builds a State from FRESH
     * {@link MaterialRegistry}/{@link MaterialBindings} instances and swaps it in
     * atomically; a published State must be treated as read-only — callers must never
     * mutate the contained registry/bindings after {@link #swap}. (The contained types
     * are not deeply immutable; enforcing that is a future hardening — see TODO.)
     */
    // TODO(phase: hardening): consider deeply-immutable snapshot views
    public static final class State {
        private final MaterialRegistry registry;
        private final MaterialBindings bindings;

        public State(MaterialRegistry registry, MaterialBindings bindings) {
            this.registry = registry;
            this.bindings = bindings;
        }

        public MaterialRegistry registry() {
            return registry;
        }

        public MaterialBindings bindings() {
            return bindings;
        }
    }

    private ActiveMaterials() {
    }

    /** Empty initial state so reads before the first reload still return non-null registries. */
    private static volatile State active = new State(new MaterialRegistry(), new MaterialBindings());

    /** The material registry currently in force. Never {@code null}. */
    public static MaterialRegistry registry() {
        return active.registry();
    }

    /** The block→material bindings currently in force. Never {@code null}. */
    public static MaterialBindings bindings() {
        return active.bindings();
    }

    /**
     * The current state snapshot (registry + bindings). Never {@code null}.
     *
     * <p>Before the first {@code SERVER_DATA} reload the registry is empty: {@code get}
     * returns {@link java.util.Optional#empty()}, and {@code getOrFallback} throws
     * {@link IllegalStateException} because {@code orge:generic_solid} has not yet been
     * loaded. The state is fully usable only after the first successful reload populates
     * the fallback material.</p>
     */
    public static State current() {
        return active;
    }

    /**
     * Builds a fresh {@link State} from raw datapack JSON, leaving the active state
     * untouched.
     *
     * @param materials material id → JSON body (from {@code data/<ns>/orge/materials/})
     * @param bindings  bindings JSON elements (from {@code data/<ns>/orge/bindings/})
     * @return a fresh, fully-populated state
     * @throws IllegalArgumentException if any entry fails to decode (propagated from
     *                                  {@link MaterialData}); the active state is NOT modified
     */
    public static State buildState(Map<Identifier, JsonElement> materials, List<JsonElement> bindings) {
        MaterialRegistry registry = new MaterialRegistry();
        MaterialBindings binds = new MaterialBindings();
        // Both calls run against the fresh instances only; on failure they throw
        // before we ever reach swap(), so the live state is preserved.
        MaterialData.loadMaterials(materials, registry);
        MaterialData.loadBindings(bindings, binds);
        return new State(registry, binds);
    }

    /** Atomically publishes {@code next} as the active state. */
    public static void swap(State next) {
        if (next == null) {
            throw new NullPointerException("next state must not be null");
        }
        active = next;
    }

    /**
     * Convenience: build a fresh state from raw JSON and, only if that succeeds,
     * swap it in. A failure throws and leaves the active state untouched.
     *
     * @return the newly-active state
     */
    public static State reloadFrom(Map<Identifier, JsonElement> materials, List<JsonElement> bindings) {
        State next = buildState(materials, bindings);
        swap(next);
        return next;
    }
}
