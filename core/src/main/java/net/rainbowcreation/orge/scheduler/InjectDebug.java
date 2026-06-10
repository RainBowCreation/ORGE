package net.rainbowcreation.orge.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic tracing for the placement-injection pipeline (capture → register → drain → dispatch →
 * gate/clear). Pure observability — no behaviour. Every log line is gated on {@link #on()} AND on the
 * event being an actual injection event, so idle play and the headless suites stay silent.
 *
 * <p>Off by default. Enable at startup with the JVM flag {@code -Dorge.debug.inject=true}, or at
 * runtime with {@code /orge debug on|off} (op). Logs go to the {@code ORGE-INJECT} logger at INFO
 * so they appear in the normal server console.</p>
 */
public final class InjectDebug {

    public static final Logger LOG = LoggerFactory.getLogger("ORGE-INJECT");

    /** Master toggle. Default OFF; enable via {@code -Dorge.debug.inject=true} or {@code /orge debug on}. */
    public static volatile boolean ON =
            Boolean.parseBoolean(System.getProperty("orge.debug.inject", "false"));

    private static final java.util.Map<String, Long> LAST_LOG = new java.util.concurrent.ConcurrentHashMap<>();

    private InjectDebug() {
    }

    public static boolean on() {
        return ON;
    }

    /** Rate-limit a noisy diagnostic: true at most once per {@code minIntervalMs} for a given {@code key}.
     *  Used for the per-setBlock wake-entry / capture-bail traces so fluid flow doesn't flood the log. */
    public static boolean throttle(String key, long minIntervalMs) {
        long now = System.currentTimeMillis();
        Long prev = LAST_LOG.get(key);
        if (prev != null && now - prev < minIntervalMs) {
            return false;
        }
        LAST_LOG.put(key, now);
        return true;
    }

    /** Compact "id(mv=true)" / "null" describer for a material in a log line. */
    public static String describe(net.rainbowcreation.orge.material.Material m) {
        if (m == null) {
            return "null";
        }
        return m.id() + "(mv=" + m.movable() + ")";
    }

    /** Render only the non-zero entries of a per-species ledger array, e.g. "[2=1000.0]". */
    public static String nonzero(float[] arr) {
        if (arr == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != 0f) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(i).append('=').append(arr[i]);
                first = false;
            }
        }
        return sb.append(']').toString();
    }
}
