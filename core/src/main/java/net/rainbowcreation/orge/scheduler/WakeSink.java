package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * The pure seam the loaders push wake events into (DESIGN §10 Decision 11). Once a section is dormant
 * it is no longer snapshotted, so the snapshot-diff ({@link CellMaterialTracker}) cannot notice a
 * change — wake MUST be explicit. The exhaustive trigger set (a missed trigger = stale frozen fluid):
 * <ul>
 *   <li>(a) block placed/broken/changed incl. bucket → {@link #wakeBlock} (resolves to the owning section);</li>
 *   <li>(b) temperature source added/removed (B+C pins) → {@link #wakeThermalSection};</li>
 *   <li>(c) neighbour pushes flux across the shared seam → {@link #wakeFlowSection} on the adjacent section;</li>
 *   <li>(d) section newly enters player range → handled by the active-set's "unseen = active" rule.</li>
 * </ul>
 * Implemented by {@link ActiveSet}. Server-thread confined.
 */
public interface WakeSink {

    /** (a) A block at world coords changed (place/break/bucket/setblock/piston): wake the owning section. */
    void wakeBlock(Identifier dim, int blockX, int blockY, int blockZ);

    /** (a') A block was BROKEN at world coords: durable removal → vacuum + wake (spec durable-material
     *  Part 4). The break signal is authoritative (the outgoing block is never read). Default = a plain
     *  {@link #wakeBlock}, so impls that carry no durable-material seam (ActiveSet, ServerStoreWriteSink,
     *  WakePlatform passthrough) need no change; {@code MinecraftThermalWorld}'s capturing sink overrides
     *  it to enqueue the removal→vacuum intent. */
    default void wakeBreak(Identifier dim, int blockX, int blockY, int blockZ) {
        wakeBlock(dim, blockX, blockY, blockZ);
    }

    /** (c) A neighbour pushed mass across the shared seam: wake the flow pass of one adjacent section. */
    void wakeFlowSection(Identifier dim, SubchunkKey key);

    /** (b) A temperature source (B+C pin) was added/removed in this section: wake its thermal pass. */
    void wakeThermalSection(Identifier dim, SubchunkKey key);
}
