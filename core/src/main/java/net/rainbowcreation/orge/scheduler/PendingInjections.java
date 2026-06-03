package net.rainbowcreation.orge.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Server-thread-confined queue of placement intents (spec Part B2), keyed by {@code (dim, cx, cz,
 * engineCell)}. The queue is the durable source of truth: an intent persists across any number of
 * stale in-flight engine steps until a successful, non-held write-back {@link #remove}s it — so a
 * stale background step cannot lose a placement (the vanish-race fix). Last-write-wins per cell
 * (Plan-1 carry-in: same-cell placements in one window collapse to the newest intent).
 *
 * <p>NOT thread-safe; all access is on the server thread (the wake hooks, snapshot, and write-back
 * all run there).</p>
 *
 * <p>Storage shape: {@code Map<Identifier, Map<Long, Map<Integer, Intent>>>}
 * = dim → packCol(cx,cz) → cell → Intent.
 * {@code packCol(cx,cz) = (cx & 0xFFFFFFFFL) | ((long) cz << 32)} — two full 32-bit signed
 * integers fit collision-free in one long; {@code cell ∈ [0, 98304)} lives in its own {@code Integer}
 * key so no packing collision is possible.</p>
 */
public final class PendingInjections {

    /** Shared empty queue for non-Minecraft {@link ThermalWorld} impls + test fakes (see
     *  {@link ThermalWorld#pendingInjections()}). A real empty queue: {@code remove}/{@code peekColumn}
     *  are harmless no-ops on it, and nothing is ever enqueued, so it stays empty. */
    public static final PendingInjections EMPTY = new PendingInjections();

    /** One placement or removal intent. {@code cell} is the engine index {@code x + 16*y + 6144*z}.
     *  When {@code removal} is {@code true} the drain stomps the cell to the index-0 vacuum sentinel
     *  (matIx 0, mass 0) and emits NO engine injection; {@code species} is {@link
     *  net.rainbowcreation.orge.section.MaterialPalette#VACUUM_ID} and {@code mass} is 0. */
    public record Intent(
            Identifier dim,
            int cx,
            int cz,
            int cell,
            Identifier species,
            float mass,
            float temperature,
            boolean removal) {

        /** Placement (non-removal) convenience: {@code removal} defaults to {@code false}. */
        public Intent(Identifier dim, int cx, int cz, int cell,
                      Identifier species, float mass, float temperature) {
            this(dim, cx, cz, cell, species, mass, temperature, false);
        }
    }

    // dim -> packCol(cx,cz) -> cell -> Intent
    private final Map<Identifier, Map<Long, Map<Integer, Intent>>> byDim = new HashMap<>();

    /** Standard chunk pack: low 32 bits = cx (signed), high 32 bits = cz (signed). Unique per column. */
    private static long packCol(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    /** Record/replace a placement intent for {@code (dim, cx, cz, cell)} (last-write-wins). */
    public void enqueue(Identifier dim, int cx, int cz, int cell,
                        Identifier species, float mass, float temperature) {
        byDim.computeIfAbsent(dim, k -> new HashMap<>())
                .computeIfAbsent(packCol(cx, cz), k -> new HashMap<>())
                .put(cell, new Intent(dim, cx, cz, cell, species, mass, temperature));
    }

    /** Record a BREAK removal intent for {@code (dim, cx, cz, cell)}: the drain stomps the cell to the
     *  index-0 vacuum sentinel (matIx 0, mass 0) and emits NO injection. Last-write-wins per cell
     *  (a removal replaces a stale placement at the same cell, and vice-versa). */
    public void enqueueRemoval(Identifier dim, int cx, int cz, int cell) {
        byDim.computeIfAbsent(dim, k -> new HashMap<>())
                .computeIfAbsent(packCol(cx, cz), k -> new HashMap<>())
                .put(cell, new Intent(dim, cx, cz, cell,
                        net.rainbowcreation.orge.section.MaterialPalette.VACUUM_ID, 0f, 0f, true));
    }

    /**
     * True iff the cell {@code (dim, cx, cz, cell)} currently holds a queued <b>removal</b> intent (a
     * same-window BREAK). A subsequent placement on the same key overwrites the removal (last-write-wins),
     * so this returns {@code false} again once a place supersedes it. Used by the placement capture to
     * detect a same-window break+replace: re-placing the species the cell already had is normally a
     * self-write no-op, but with a pending removal it must be captured (placement-into-vacuum) so the lone
     * removal can't stomp the engine cell to vacuum under a still-solid durable identity.
     */
    public boolean hasPendingRemoval(Identifier dim, int cx, int cz, int cell) {
        Map<Long, Map<Integer, Intent>> dimMap = byDim.get(dim);
        if (dimMap == null) {
            return false;
        }
        Map<Integer, Intent> colMap = dimMap.get(packCol(cx, cz));
        if (colMap == null) {
            return false;
        }
        Intent in = colMap.get(cell);
        return in != null && in.removal();
    }

    /**
     * Intents whose cell lies in column {@code (dim, cx, cz)}, in deterministic ascending-cell
     * order. Does NOT remove them (they stay queued until {@link #remove} after a successful
     * write-back).
     */
    public List<Intent> peekColumn(Identifier dim, int cx, int cz) {
        Map<Long, Map<Integer, Intent>> dimMap = byDim.get(dim);
        if (dimMap == null) {
            return Collections.emptyList();
        }
        Map<Integer, Intent> colMap = dimMap.get(packCol(cx, cz));
        if (colMap == null || colMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<Intent> result = new ArrayList<>(colMap.values());
        result.sort((a, b) -> Integer.compare(a.cell(), b.cell()));
        return result;
    }

    /** Remove the given intents (called only after a successful, non-held write-back). */
    public void remove(Collection<Intent> intents) {
        for (Intent intent : intents) {
            Map<Long, Map<Integer, Intent>> dimMap = byDim.get(intent.dim());
            if (dimMap == null) continue;
            Map<Integer, Intent> colMap = dimMap.get(packCol(intent.cx(), intent.cz()));
            if (colMap == null) continue;
            colMap.remove(intent.cell(), intent);
        }
    }

    /** Drop everything for one chunk column (call on chunk unload to bound memory). */
    public void forgetColumn(Identifier dim, int cx, int cz) {
        Map<Long, Map<Integer, Intent>> dimMap = byDim.get(dim);
        if (dimMap != null) {
            dimMap.remove(packCol(cx, cz));
        }
    }

    /** Drop all intents (server stop). */
    public void clear() {
        byDim.clear();
    }

    /** Total queued intents (test/diagnostics). */
    public int size() {
        int total = 0;
        for (Map<Long, Map<Integer, Intent>> dimMap : byDim.values()) {
            for (Map<Integer, Intent> colMap : dimMap.values()) {
                total += colMap.size();
            }
        }
        return total;
    }
}
