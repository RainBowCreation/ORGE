package net.rainbowcreation.orge.protocol;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.Orge;

/**
 * Wire protocol channel ids for server ↔ worker traffic over Minecraft custom payloads
 * (DESIGN.md "Wire protocol"). The <i>engine</i> call itself is an in-process FFI
 * invocation; this protocol is only the network hop between the scheduler and clients.
 *
 * <ul>
 *   <li>{@link #ASSIGN}      — subchunk keys this client owns next tick, with versions.</li>
 *   <li>{@link #GEOMETRY}    — (on demand) material-index + mass arrays for a version.</li>
 *   <li>{@link #STEP_INPUT}  — per assigned subchunk: temperature array + neighbour halo.</li>
 *   <li>{@link #STEP_RESULT} — per assigned subchunk: new temperature (Phase-2: + mass).</li>
 *   <li>{@link #HEALTH}      — client compute time / deadline status (drives throttling).</li>
 * </ul>
 *
 * <p>The concrete payload records + codecs and the Architectury networking
 * registration are deferred to the networking phase.</p>
 */
public final class OrgePackets {

    public static final Identifier ASSIGN = id("assign");
    public static final Identifier GEOMETRY = id("geometry");
    public static final Identifier STEP_INPUT = id("step_input");
    public static final Identifier STEP_RESULT = id("step_result");
    public static final Identifier HEALTH = id("health");

    private OrgePackets() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Orge.MOD_ID, path);
    }

    // TODO(phase: networking): payload records (AssignPayload, GeometryPayload,
    //  StepInputPayload, StepResultPayload, HealthPayload) with StreamCodecs, registered
    //  through dev.architectury.networking.NetworkManager on both sides.
}
