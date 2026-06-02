package net.rainbowcreation.orge.phase;

import net.rainbowcreation.orge.material.Material;

import java.util.List;

/**
 * Resolves the {@link Material} a cell BECAME this engine step from the native step's {@code matOut}
 * array — the post-swap species — falling back to a supplied live-block material when the engine
 * reported none. Pure (no Minecraft world access), so the §7 changer can pair each cell with the
 * SAME species the engine produced rather than the stale pre-swap block.
 *
 * <p>Why this matters: the native molar-sort swaps fluids vertically (lava sinks under water), and
 * blocks are only rewritten by the reconciler AFTER the phase changer runs. Reading material from
 * the live block therefore paired a swapped-in temperature with the OUTGOING material — e.g. the
 * risen water carried its cool temperature while the block still read lava, so {@code PhaseRule} saw
 * {@code cool < lava.minTemp} and froze it to {@code lava.minTarget} = stone. Sourcing material from
 * {@code matOut} keeps material and temperature on the same post-swap snapshot.</p>
 *
 * <p>Mirrors the resolution {@code MinecraftFluidReconciler} already applies to {@code matOut}.</p>
 */
public final class EngineOutSpecies {

    private EngineOutSpecies() {}

    /**
     * @param outMaterial the engine's per-cell output species (the {@code matOut} array), or null on a
     *                    non-advection / back-compat path
     * @param outLut      the step's batch material table that resolves those indices, or null
     * @param i           section-local cell index
     * @param fallback    the live-block material to use when the engine reported no species for this
     *                    cell (null arrays, the index-0 VOID/air sentinel, or an out-of-range index)
     * @return the post-swap engine species, or {@code fallback}
     */
    public static Material resolve(char[] outMaterial, List<Material> outLut, int i, Material fallback) {
        if (outMaterial == null || outLut == null || i < 0 || i >= outMaterial.length) {
            return fallback;
        }
        int s = outMaterial[i];
        if (s == 0 || s >= outLut.size()) {
            return fallback; // VOID/air sentinel or unknown index — defer to the live block
        }
        return outLut.get(s);
    }
}
