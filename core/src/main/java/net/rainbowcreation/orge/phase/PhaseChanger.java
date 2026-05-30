package net.rainbowcreation.orge.phase;

import net.rainbowcreation.orge.scheduler.ThermalWorld;

/**
 * The §7 seam the {@link net.rainbowcreation.orge.scheduler.Scheduler} calls once per
 * written section, after a successful write-back, to apply phase transitions. Keeps the
 * scheduler loader-free; the live implementation ({@code MinecraftPhaseChanger}) reads the
 * just-written temperatures + current blocks and places target blocks on the server thread.
 */
public interface PhaseChanger {

    /** Apply phase changes for one written section (server thread). */
    void applyPhaseChanges(ThermalWorld.BatchEntry entry);

    /** A no-op used in tests and any configuration without phase change. */
    PhaseChanger NOOP = entry -> {};
}
