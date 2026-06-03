package net.rainbowcreation.orge.platform.fabric;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.WakeSink;

/**
 * Fabric implementation of {@code WakePlatform} (matched by {@code <name>Impl}). Fabric has no common
 * "block changed" callback, so the signal comes from a {@code Level#setBlock} mixin
 * ({@code WakeSetBlockMixin}) which calls {@link #wake}. This impl just stashes the sink the mixin
 * forwards into &mdash; the static-bridge pattern already in the repo
 * ({@code OrgeFluidPolicy.setManagedSectionPredicate} / {@code OrgeFluidSuppressionBridge}).
 */
public final class WakePlatformImpl {

    private static volatile WakeSink SINK;

    private WakePlatformImpl() {
    }

    public static void registerBlockChangeWake(WakeSink sink) {
        SINK = sink;
    }

    /** Called from {@code WakeSetBlockMixin} at the TAIL of a successful server-side {@code Level#setBlock}. */
    public static void wake(Identifier dim, int x, int y, int z) {
        WakeSink s = SINK;
        if (net.rainbowcreation.orge.scheduler.InjectDebug.on()
                && net.rainbowcreation.orge.scheduler.InjectDebug.throttle("fabric-wake", 500)) {
            net.rainbowcreation.orge.scheduler.InjectDebug.LOG.info(
                    "[fabric-wake] setBlock mixin fired at ({},{},{}) sink={}", x, y, z, s != null);
        }
        if (s != null) {
            s.wakeBlock(dim, x, y, z);
        }
    }
}
