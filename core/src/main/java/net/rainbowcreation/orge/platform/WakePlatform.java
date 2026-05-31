package net.rainbowcreation.orge.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.WakeSink;

/**
 * Loader-specific low-level block-change wake hook (DESIGN &sect;10 Decision 11, trigger (a)). The common
 * {@code BlockEvent.PLACE/BREAK} + {@code PlayerEvent.FILL_BUCKET} (wired in {@code Orge.init()}) cover
 * player-driven edits; this seam covers the rest of the {@code Level.setBlock} fan-out that has NO
 * Architectury common event &mdash; {@code /setblock}, datapack/command edits, pistons, dispenser/bucket
 * placements, and the reconciler's own {@code air&rarr;fluid} writes &mdash; so a dormant section near a
 * programmatic change still wakes (a missed trigger = stale frozen fluid).
 *
 * <p>Each impl registers its loader-native block-change signal (NeoForge {@code NeighborNotifyEvent} /
 * a Fabric {@code Level#setBlock} mixin), guards server-side, derives the dimension
 * {@link Identifier} from {@code serverLevel.dimension().identifier()}, and forwards the world block
 * coords to {@code sink.wakeBlock(dim, x, y, z)}. Server-side only; ORGE state is server-authoritative.
 * Mirrors {@link net.rainbowcreation.orge.section.SectionStorePlatform} (chunk hooks have no common event
 * either).</p>
 */
public final class WakePlatform {

    private WakePlatform() {
    }

    /**
     * Registers the per-loader block-change listener that pushes wakes into {@code sink}. Called once
     * from {@code Orge.init()} (after the sink is constructed).
     *
     * @param sink the shared, server-thread-confined wake sink (the active-set)
     */
    @ExpectPlatform
    public static void registerBlockChangeWake(WakeSink sink) {
        throw new AssertionError("ExpectPlatform implementation not found");
    }
}
