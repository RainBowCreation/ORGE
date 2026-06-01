package net.rainbowcreation.orge.phase;

/**
 * Pure mass → vanilla fluid render-level mapping (DESIGN §10 Decision 9). LEVEL is a visual
 * depth readout of mass fraction {@code f = m / fullMass}: {@code f >= 0.95} renders a full
 * block (level 0); {@code 0 < f < 0.95} renders {@code round((1-f)*7)} clamped 1..7; {@code f<=0}
 * means the cell is empty and the fluid block should be removed ({@link #REMOVE}). No Minecraft
 * types — the MC adapter ({@code MinecraftFluidReconciler}) turns the level into a block state.
 */
public final class FluidReconcileLogic {

    /** Sentinel: the cell holds no fluid; remove any managed fluid block. */
    public static final int REMOVE = -1;

    /** Fraction above which the cell renders as a full (level-0) block. */
    public static final float FULL_FRACTION = 0.95f;

    private FluidReconcileLogic() {}

    /** {@code m / fullMass}, guarded for a zero/negative full mass (→ 0). */
    public static float fraction(float massKg, float fullMassKg) {
        if (fullMassKg <= 0f) return 0f;
        float f = massKg / fullMassKg;
        return f < 0f ? 0f : f;
    }

    /** Maps a mass fraction to a vanilla fluid LEVEL, or {@link #REMOVE} when empty. */
    public static int levelForFraction(float f) {
        if (f <= 0f) return REMOVE;
        if (f >= FULL_FRACTION) return 0;
        int level = Math.round((1f - f) * 7f);
        if (level < 1) return 1;
        if (level > 7) return 7;
        return level;
    }

    /**
     * The reconcile <b>render bucket</b> for a level (Decision 13b throttle). A render level already
     * partitions mass into discrete buckets ({@link #REMOVE}, 0, 1..7), so the bucket of a level is
     * the level itself. The reconciler writes a block only when the new mass's bucket differs from
     * the bucket the world block currently shows — mass can move within a bucket without a packet.
     */
    public static int levelBucket(int level) {
        return level;
    }

    /**
     * The render-throttle decision (Decision 13b), made species-aware so a vacated fluid cell is
     * never silently kept. The level-bucket throttle is a valid "skip the packet" optimization ONLY
     * when the cell's species did not change: same fluid whose mass merely moved within a render
     * bucket needs no write. A SPECIES CHANGE (e.g. a cell that engine Pass A turned from water into
     * the air that rose from below, or water→lava) must ALWAYS reconcile — even when the numeric
     * render level coincides — otherwise the stale block is never replaced and a falling column
     * leaves a duplicate trail.
     *
     * @param sameSpecies  true when the cell's new species equals the world block's species
     * @param renderLevel  the level the new mass renders at ({@link #REMOVE} or 0..7)
     * @param currentBucket the bucket the world block currently shows ({@link #bucketOfWorldBlock})
     * @return true to SKIP the write (throttle); false to reconcile
     */
    public static boolean throttles(boolean sameSpecies, int renderLevel, int currentBucket) {
        return sameSpecies && levelBucket(renderLevel) == currentBucket;
    }
}
