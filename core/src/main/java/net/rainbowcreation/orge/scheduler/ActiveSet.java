package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-section dormancy roster (DESIGN §10 Decision 11). Holds one {@link SettleCountdown} per
 * tracked section; the snapshot steps only sections that are NOT fully asleep. Mirrors
 * {@link CellMaterialTracker}'s shape and lifecycle (server-thread confined, pruned per column on
 * chunk unload), so a plain {@link HashMap} needs no synchronization.
 *
 * <p>Implements {@link WakeSink}: the loader event hooks push wakes here. A section the set has never
 * seen is treated as active (the new-in-range rule, trigger (d)).</p>
 *
 * <p>Internal handling of the immutable {@link SettleCountdown} record: every mutating operation
 * (noteFlow/noteThermal/wake*) calls the corresponding pure factory method on the existing record and
 * replaces the map entry with the returned instance — no aliasing, server-thread confined.</p>
 */
public final class ActiveSet implements WakeSink {

    /** dim → column-key → sectionY → countdown. Mirrors CellMaterialTracker's nesting. */
    private final Map<Identifier, Map<Long, Map<Integer, SettleCountdown>>> byDim = new HashMap<>();

    /** Standard chunk pack: low 32 bits cx, high 32 bits cz (unique per column). */
    private static long col(int cx, int cz) {
        return (cx & 0xffffffffL) | (((long) cz) << 32);
    }

    /** Returns the countdown for {@code key} in {@code dim}, or {@code null} if untracked. */
    private SettleCountdown get(Identifier dim, SubchunkKey key) {
        Map<Long, Map<Integer, SettleCountdown>> d = byDim.get(dim);
        if (d == null) return null;
        Map<Integer, SettleCountdown> c = d.get(col(key.cx(), key.cz()));
        return c == null ? null : c.get(key.sectionY());
    }

    /** Stores (or replaces) the countdown for {@code key} in {@code dim}. */
    private void put(Identifier dim, SubchunkKey key, SettleCountdown c) {
        byDim.computeIfAbsent(dim, k -> new HashMap<>())
                .computeIfAbsent(col(key.cx(), key.cz()), k -> new HashMap<>())
                .put(key.sectionY(), c);
    }

    /**
     * Filter {@code inRange} (the player-range sphere keys) down to the sections to actually step this
     * cycle: a never-seen section is admitted active (new-in-range, trigger (d)) and recorded; a tracked
     * section is admitted iff it is not fully {@link SettleCountdown#asleep() asleep}. Server thread only.
     */
    public List<SubchunkKey> activeWithin(Identifier dim, List<SubchunkKey> inRange) {
        List<SubchunkKey> out = new ArrayList<>(inRange.size());
        for (SubchunkKey key : inRange) {
            SettleCountdown c = get(dim, key);
            if (c == null) {
                put(dim, key, SettleCountdown.active()); // new-in-range starts active
                out.add(key);
            } else if (!c.asleep()) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * Advection writeback bookkeeping: replace the countdown for this key with the result of
     * {@link SettleCountdown#noteFlow(float)} — immutable record, so map-replace pattern.
     */
    public void noteFlowDelta(Identifier dim, SubchunkKey key, float maxMassDelta) {
        SettleCountdown c = get(dim, key);
        if (c == null) c = SettleCountdown.active();
        put(dim, key, c.noteFlow(maxMassDelta));
    }

    /**
     * Conduction writeback bookkeeping: replace the countdown for this key with the result of
     * {@link SettleCountdown#noteThermal(float)} — immutable record, so map-replace pattern.
     */
    public void noteThermalDelta(Identifier dim, SubchunkKey key, float maxTempDelta) {
        SettleCountdown c = get(dim, key);
        if (c == null) c = SettleCountdown.active();
        put(dim, key, c.noteThermal(maxTempDelta));
    }

    /** Returns {@code true} iff the section is tracked and its flow pass is dormant. */
    public boolean isFlowDormant(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        return c != null && c.flowDormant();
    }

    /** Returns {@code true} iff the section is tracked and its thermal pass is dormant. */
    public boolean isThermalDormant(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        return c != null && c.thermalDormant();
    }

    /** Returns {@code true} iff the section is tracked and both passes are dormant (fully asleep). */
    public boolean isAsleep(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        return c != null && c.asleep();
    }

    /**
     * Explicit flow wake (trigger (a)/(c)): replace the countdown with {@link SettleCountdown#wakeFlow()}.
     * Creates the entry active if the section was untracked.
     */
    public void wakeFlow(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        put(dim, key, c == null ? SettleCountdown.active() : c.wakeFlow());
    }

    /**
     * Explicit thermal wake (trigger (b)): replace the countdown with {@link SettleCountdown#wakeThermal()}.
     * Creates the entry active if the section was untracked.
     */
    public void wakeThermal(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        put(dim, key, c == null ? SettleCountdown.active() : c.wakeThermal());
    }

    // ---- WakeSink implementation ----

    /**
     * (a) A block at world coords changed (place/break/bucket/setblock/piston): resolve to the owning
     * {@link SubchunkKey} via {@code blockCoord >> 4} (same as
     * {@code SectionPos.blockToSectionCoord}, already used by {@code MinecraftThermalWorld.snapshot})
     * and wake both passes (a block edit can affect both mass and temperature).
     */
    @Override
    public void wakeBlock(Identifier dim, int blockX, int blockY, int blockZ) {
        SubchunkKey key = new SubchunkKey(
                SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockY),
                SectionPos.blockToSectionCoord(blockZ));
        SettleCountdown c = get(dim, key);
        put(dim, key, c == null ? SettleCountdown.active() : c.wakeAll());
    }

    /**
     * (c) A neighbour pushed mass across the shared seam: wake only the flow pass of this adjacent section.
     */
    @Override
    public void wakeFlowSection(Identifier dim, SubchunkKey key) {
        wakeFlow(dim, key);
    }

    /**
     * (b) A temperature source (B+C pin) was added/removed in this section: wake only the thermal pass.
     */
    @Override
    public void wakeThermalSection(Identifier dim, SubchunkKey key) {
        wakeThermal(dim, key);
    }

    /**
     * Drop every tracked section of one chunk column (chunk unload). Exactly mirrors
     * {@link CellMaterialTracker#forgetColumn(Identifier, int, int)}.
     */
    public void forgetColumn(Identifier dim, int cx, int cz) {
        Map<Long, Map<Integer, SettleCountdown>> d = byDim.get(dim);
        if (d != null) d.remove(col(cx, cz));
    }

    /** Drop all tracked countdowns (server stop). */
    public void clear() {
        byDim.clear();
    }
}
