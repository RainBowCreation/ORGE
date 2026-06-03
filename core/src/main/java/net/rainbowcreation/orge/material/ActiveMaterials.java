package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * The active, in-game material state (DESIGN.md §6): the {@link MaterialRegistry}
 * currently in force, swapped wholesale on each {@code /reload} by
 * {@link MaterialJsonLoader}.
 *
 * <p>The reference is {@code volatile} so the reload listener can publish a fresh
 * registry from the off-thread reload worker and have readers on other threads observe
 * it without further synchronization.</p>
 *
 * <h2>Atomicity</h2>
 * <p>{@link #buildState(Map)} constructs a fresh {@link State} from raw JSON
 * <em>without touching the active state</em>; if {@link MaterialData} rejects the
 * input it throws and the currently-active state is left untouched. Only a
 * successfully-built state is ever handed to {@link #swap(State)}. This is what
 * makes a failed reload non-corrupting.</p>
 */
public final class ActiveMaterials {

    /**
     * A published materials state. The reload listener builds a State from a FRESH
     * {@link MaterialRegistry} instance and swaps it in atomically; a published State
     * must be treated as read-only — callers must never mutate the contained registry
     * after {@link #swap}. (The contained type is not deeply immutable; enforcing that
     * is a future hardening — see TODO.)
     */
    // TODO(phase: hardening): consider deeply-immutable snapshot views
    public static final class State {
        private final MaterialRegistry registry;
        private final java.util.List<Material> orderedMaterials;          // slot -> material (slot 0 = VACUUM)
        private final java.util.Map<Identifier, Character> materialSlots;  // id -> fixed slot
        private volatile int lutEpoch = 0;                                 // assigned once by swap()

        public State(MaterialRegistry registry) {
            this.registry = registry;
            this.orderedMaterials = MaterialTable.ordered(registry);
            this.materialSlots = MaterialTable.slots(this.orderedMaterials);
        }

        public MaterialRegistry registry() {
            return registry;
        }

        /** The stable ordered table (slot 0 = VACUUM); the engine is registered from this list. */
        public java.util.List<Material> orderedMaterials() {
            return orderedMaterials;
        }

        /** Material id -> fixed slot, for building a MaterialLut view at assembly time. */
        public java.util.Map<Identifier, Character> materialSlots() {
            return materialSlots;
        }

        /** The generation tag selecting this State's resident engine table. */
        public int lutEpoch() {
            return lutEpoch;
        }

        /** One-shot epoch assignment at publish time (package-private; called only by swap). */
        void assignEpoch(int epoch) {
            this.lutEpoch = epoch;
        }
    }

    private ActiveMaterials() {
    }

    private static final java.util.concurrent.atomic.AtomicInteger EPOCHS =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** Empty initial state so reads before the first reload still return non-null registries. */
    private static volatile State active = new State(new MaterialRegistry());

    /** The material registry currently in force. Never {@code null}. */
    public static MaterialRegistry registry() {
        return active.registry();
    }

    /**
     * The current state snapshot (registry). Never {@code null}.
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
     * @return a fresh, fully-populated state
     * @throws IllegalArgumentException if any entry fails to decode (propagated from
     *                                  {@link MaterialData}); the active state is NOT modified
     */
    public static State buildState(Map<Identifier, JsonElement> materials) {
        MaterialRegistry registry = new MaterialRegistry();
        // Runs against the fresh instance only; on failure it throws
        // before we ever reach swap(), so the live state is preserved.
        MaterialData.loadMaterials(materials, registry);
        return new State(registry);
    }

    /** Atomically publishes {@code next} as the active state. */
    public static void swap(State next) {
        if (next == null) {
            throw new NullPointerException("next state must not be null");
        }
        int epoch = EPOCHS.incrementAndGet();
        next.assignEpoch(epoch);
        active = next;
        net.rainbowcreation.orge.engine.EngineFactory.instance()
                .registerMaterials(epoch, next.orderedMaterials());
    }

    /**
     * Convenience: build a fresh state from raw JSON and, only if that succeeds,
     * swap it in. A failure throws and leaves the active state untouched.
     *
     * @return the newly-active state
     */
    public static State reloadFrom(Map<Identifier, JsonElement> materials) {
        State next = buildState(materials);
        swap(next);
        return next;
    }
}
