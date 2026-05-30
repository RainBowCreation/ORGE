package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory record of the material each simulated cell's stored values belong to (DESIGN §10
 * follow-on). The §5 {@link net.rainbowcreation.orge.section.SectionStore} persists only
 * temperature + mass — never material identity (by design) — so when a cell's block changes material
 * (a bucket placement, a piston, {@code /setblock}) the stored values become stale relative to the
 * new block. This tracker remembers, per section, the live per-cell material ids from the previous
 * snapshot, so {@link MaterialChangeReseed} can detect the change and refresh the cell.
 *
 * <p>Server-thread confined: {@link #prior}/{@link #record} run inside the snapshot and
 * {@link #forgetColumn} on the chunk-unload hook, both on the server thread, so a plain
 * {@link HashMap} needs no synchronization. Entries are dropped per column on chunk unload, so the
 * tracker holds signatures only for currently-loaded sections (it does not grow with explored area).</p>
 */
public final class CellMaterialTracker {

    private final Map<Identifier, Map<Long, Map<Integer, Identifier[]>>> byDim = new HashMap<>();

    /** Standard chunk pack: low 32 bits cx, high 32 bits cz (unique per column). */
    private static long col(int cx, int cz) {
        return (cx & 0xffffffffL) | (((long) cz) << 32);
    }

    /** The per-cell material ids recorded for {@code key} last cycle, or {@code null} if untracked. */
    public Identifier[] prior(Identifier dim, SubchunkKey key) {
        Map<Long, Map<Integer, Identifier[]>> d = byDim.get(dim);
        if (d == null) {
            return null;
        }
        Map<Integer, Identifier[]> c = d.get(col(key.cx(), key.cz()));
        return c == null ? null : c.get(key.sectionY());
    }

    /** Records the live per-cell material ids that this section's stored values now belong to. */
    public void record(Identifier dim, SubchunkKey key, Identifier[] ids) {
        byDim.computeIfAbsent(dim, k -> new HashMap<>())
                .computeIfAbsent(col(key.cx(), key.cz()), k -> new HashMap<>())
                .put(key.sectionY(), ids);
    }

    /** Drops every tracked section of one chunk column (called on chunk unload to bound memory). */
    public void forgetColumn(Identifier dim, int cx, int cz) {
        Map<Long, Map<Integer, Identifier[]>> d = byDim.get(dim);
        if (d != null) {
            d.remove(col(cx, cz));
        }
    }

    /** Drops all tracked signatures (server stop). */
    public void clear() {
        byDim.clear();
    }
}
