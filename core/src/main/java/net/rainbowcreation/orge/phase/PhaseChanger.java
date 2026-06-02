package net.rainbowcreation.orge.phase;

import java.util.List;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.ThermalWorld;

/**
 * The §7 seam the {@link net.rainbowcreation.orge.scheduler.Scheduler} calls once per
 * written section, after a successful write-back, to apply phase transitions. Keeps the
 * scheduler loader-free; the live implementation ({@code MinecraftPhaseChanger}) reads the
 * just-written temperatures + the engine's output species and places target blocks on the
 * server thread.
 */
public interface PhaseChanger {

    /** Apply phase changes for one written section (server thread). */
    void applyPhaseChanges(ThermalWorld.BatchEntry entry);

    /**
     * Apply phase changes pairing each cell's just-written temperature with the species the engine
     * says it BECAME this step ({@code outMaterial}, the {@code matOut} array, resolved through the
     * step's {@code outLut} table). This is required after the native molar-sort swaps fluids
     * vertically: the live block is not rewritten until the reconciler runs (after this), so reading
     * material from the block would pair a swapped-in temperature with the outgoing material. When
     * either argument is null the implementation falls back to the live block's material.
     */
    default void applyPhaseChanges(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut) {
        applyPhaseChanges(entry); // back-compat: NOOP and the live-block-only path
    }

    /** A no-op used in tests and any configuration without phase change. */
    PhaseChanger NOOP = entry -> {};
}
