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
 * <p><b>Revivals (issue #65):</b> a same-species re-place {@link #cancelRemoval(Identifier, int, int, int,
 * Intent) cancels} a queued BREAK removal so the cell keeps its block. If that removal was already
 * drained into the in-flight step, the step still carries the vacuum stomp. A step that is cancelled,
 * held or fails writes nothing, so the cancel stands. A step that is written back persists the vacuum
 * under the re-placed block, so {@link #remove} queues the cancelled removal's revival placement for the
 * next cycle. A later intent at the cell drops the revival (last-write-wins).</p>
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

    // cancelled removal -> the placement that restores its cell if a written-back step applied it
    private final Map<Intent, Intent> revivals = new HashMap<>();

    /** Standard chunk pack: low 32 bits = cx (signed), high 32 bits = cz (signed). Unique per column. */
    private static long packCol(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    /** Record/replace a placement intent for {@code (dim, cx, cz, cell)} (last-write-wins). */
    public void enqueue(Identifier dim, int cx, int cz, int cell,
                        Identifier species, float mass, float temperature) {
        revivals.remove(removalIntent(dim, cx, cz, cell));
        column(dim, cx, cz).put(cell, new Intent(dim, cx, cz, cell, species, mass, temperature));
    }

    /** Record a BREAK removal intent for {@code (dim, cx, cz, cell)}: the drain stomps the cell to the
     *  index-0 vacuum sentinel (matIx 0, mass 0) and emits NO injection. Last-write-wins per cell
     *  (a removal replaces a stale placement at the same cell, and vice-versa). */
    public void enqueueRemoval(Identifier dim, int cx, int cz, int cell) {
        Intent removal = removalIntent(dim, cx, cz, cell);
        revivals.remove(removal);
        column(dim, cx, cz).put(cell, removal);
    }

    /** The removal intent for a cell (every removal at a cell is equal: vacuum, 0 kg). */
    private static Intent removalIntent(Identifier dim, int cx, int cz, int cell) {
        return new Intent(dim, cx, cz, cell,
                net.rainbowcreation.orge.section.MaterialPalette.VACUUM_ID, 0f, 0f, true);
    }

    private Map<Integer, Intent> column(Identifier dim, int cx, int cz) {
        return byDim.computeIfAbsent(dim, k -> new HashMap<>())
                .computeIfAbsent(packCol(cx, cz), k -> new HashMap<>());
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
     * Drop a queued <b>removal</b> at {@code (dim, cx, cz, cell)} (no-op if the cell holds no intent, or a
     * placement rather than a removal). Used when a same-window same-species re-place supersedes a stale
     * break/air-flicker removal: the cell keeps its durable identity + stored mass, so the removal must NOT
     * stomp it to vacuum, and NO replacement injection is enqueued (which would fabricate mass).
     */
    public void cancelRemoval(Identifier dim, int cx, int cz, int cell) {
        cancelRemoval(dim, cx, cz, cell, null);
    }

    /**
     * {@link #cancelRemoval(Identifier, int, int, int)}, remembering {@code revival} — the placement of
     * the re-placed block — against the cancelled removal. If a step that already drained the removal is
     * written back, {@link #remove} queues {@code revival} so the cell does not stay vacuum (issue #65).
     * A {@code null} revival remembers nothing.
     */
    public void cancelRemoval(Identifier dim, int cx, int cz, int cell, Intent revival) {
        Map<Long, Map<Integer, Intent>> dimMap = byDim.get(dim);
        if (dimMap == null) {
            return;
        }
        Map<Integer, Intent> colMap = dimMap.get(packCol(cx, cz));
        if (colMap == null) {
            return;
        }
        Intent in = colMap.get(cell);
        if (in != null && in.removal()) {
            colMap.remove(cell);
            if (revival != null) {
                revivals.put(in, revival);
            }
        }
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

    /** Remove the given intents (called only after a successful, non-held write-back). A drained removal
     *  that a re-place cancelled mid-step was still applied by this write-back: queue its revival, unless
     *  the cell already holds a newer intent (issue #65). */
    public void remove(Collection<Intent> intents) {
        for (Intent intent : intents) {
            Map<Long, Map<Integer, Intent>> dimMap = byDim.get(intent.dim());
            Map<Integer, Intent> colMap = dimMap == null ? null : dimMap.get(packCol(intent.cx(), intent.cz()));
            if (colMap != null) {
                colMap.remove(intent.cell(), intent);
            }
            Intent revival = revivals.remove(intent);
            if (revival != null) {
                column(intent.dim(), intent.cx(), intent.cz()).putIfAbsent(intent.cell(), revival);
            }
        }
    }

    /** Drop everything for one chunk column (call on chunk unload to bound memory). */
    public void forgetColumn(Identifier dim, int cx, int cz) {
        Map<Long, Map<Integer, Intent>> dimMap = byDim.get(dim);
        if (dimMap != null) {
            dimMap.remove(packCol(cx, cz));
        }
        revivals.keySet().removeIf(r -> r.dim().equals(dim) && r.cx() == cx && r.cz() == cz);
    }

    /** Drop all intents (server stop). */
    public void clear() {
        byDim.clear();
        revivals.clear();
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
