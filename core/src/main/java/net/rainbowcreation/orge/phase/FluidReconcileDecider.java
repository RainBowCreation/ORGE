package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

/**
 * The per-cell §10 fluid-reconcile decision, decoupled from the live world. Given a cell's resolved
 * facts — the world block's material, the engine's post-swap species, the stored mass, and three
 * read-only facts about the live block (its render bucket, whether it is a managed fluid block,
 * whether it is air) — it returns the single {@link Action} the {@code MinecraftFluidReconciler}
 * must take: SKIP, CLEAR (remove a managed fluid), or PLACE a repr block at a render LEVEL.
 *
 * <p>This is the load-bearing decision the reconciler used to interleave with inline
 * {@code movable()}/{@code defaultMass()}/{@code id()} reads and {@code BlockState} branching. Here
 * the species/mass math ({@link FluidReconcileLogic}), the species-aware throttle, the §7 contact
 * whitelist, and the repr-block pick are all in this pure unit; the adapter resolves the MC-typed
 * facts once per cell and applies the {@link Action}. Pure (no {@code ServerLevel}) → it is the test
 * surface, no world mock required.</p>
 *
 * <p>Behaviour mirrors the old adapter loop exactly, in the same branch order: reconcile only a cell
 * that is or becomes a fluid; size the render level off the species the cell now holds; honour the
 * Decision 13b species-aware bucket throttle; remove only a cell that currently holds a managed
 * fluid; never stomp a non-air/non-fluid block (§7 owns water+lava→obsidian); skip a material with
 * no representative block.</p>
 */
public final class FluidReconcileDecider {

    /** What the adapter must do with a cell. */
    public enum Kind {
        /** Leave the cell untouched (non-fluid, throttled, or §7-owned). */
        SKIP,
        /** Remove the managed fluid block (→ air). */
        CLEAR,
        /** Draw {@link Action#block()} at {@link Action#renderLevel()}. */
        PLACE
    }

    /**
     * The decision for one cell. {@code block} and {@code renderLevel} are meaningful only for
     * {@link Kind#PLACE} (a {@code SKIP}/{@code CLEAR} carries no draw).
     */
    public record Action(Kind kind, Identifier block, int renderLevel) {
        private static final Action SKIP = new Action(Kind.SKIP, null, FluidReconcileLogic.REMOVE);
        private static final Action CLEAR = new Action(Kind.CLEAR, null, FluidReconcileLogic.REMOVE);
    }

    private FluidReconcileDecider() {}

    /**
     * @param worldMaterial   the live world block's material (the species the block currently shows)
     * @param outMat          the engine's post-swap species for this cell (already resolved via
     *                        {@link EngineOutSpecies}, world-block fallback), or null
     * @param massKg          the cell's stored mass
     * @param currentBucket   the render bucket the world block currently shows (REMOVE for a non-fluid,
     *                        else its {@code LiquidBlock.LEVEL} bucket)
     * @param currentIsLiquid whether the live block is a managed fluid block
     * @param currentIsAir    whether the live block is air
     */
    public static Action decide(Material worldMaterial,
                                Material outMat,
                                float massKg,
                                int currentBucket,
                                boolean currentIsLiquid,
                                boolean currentIsAir) {
        // Reconcile a cell when EITHER the world block is a managed fluid OR the engine says it is now
        // a fluid (wetting an air cell). Skip cells that are and stay non-fluid.
        boolean worldIsFluid = worldMaterial != null && worldMaterial.movable();
        boolean becameFluid = outMat != null && outMat.movable();
        if (!worldIsFluid && !becameFluid) {
            return Action.SKIP;
        }

        // The cap/full reference and the species we render is the one the cell now holds (so a wetted
        // air cell reads water's 1000 kg full reference, not air's).
        Material levelMaterial = becameFluid ? outMat : worldMaterial;
        float f = FluidReconcileLogic.fraction(massKg, levelMaterial.defaultMass());
        int renderLevel = FluidReconcileLogic.levelForFraction(f);

        // Decision 13b throttle: skip only when the SPECIES is unchanged AND mass stayed in the same
        // render bucket. A species change (incl. a fluid cell that Pass A vacated → air) must always
        // reconcile, even if the numeric render level coincides, or the stale block is never replaced.
        boolean sameSpecies = sameSpecies(levelMaterial, worldMaterial);
        if (FluidReconcileLogic.throttles(sameSpecies, renderLevel, currentBucket)) {
            return Action.SKIP;
        }

        if (renderLevel == FluidReconcileLogic.REMOVE) {
            // Only clear a cell that currently holds a managed fluid block; leave others alone. (Steam
            // renders as minecraft:air, so a vacated gas cell is already air and needs no clear.)
            return currentIsLiquid ? Action.CLEAR : Action.SKIP;
        }

        // §7 contact whitelist (Decision 7): place only over air or over a managed fluid block; never
        // stomp a solid the phase-changer produced (water+lava→obsidian etc.). (Steam is air-covered.)
        if (!currentIsAir && !currentIsLiquid) {
            return Action.SKIP;
        }

        Identifier repr = levelMaterial.representativeBlock();
        if (repr == null) {
            return Action.SKIP; // material has no representative block to place
        }
        return new Action(Kind.PLACE, repr, renderLevel);
    }

    /**
     * True when the cell's NEW species ({@code levelMaterial}) is the SAME material as the world block
     * currently shows ({@code worldMaterial}), compared by material id. Only then is the level-bucket
     * throttle allowed to skip the write. A null id on either side (defensive) counts as "changed" so
     * the cell always reconciles.
     */
    private static boolean sameSpecies(Material levelMaterial, Material worldMaterial) {
        if (levelMaterial == worldMaterial) {
            return true;
        }
        if (levelMaterial == null || worldMaterial == null) {
            return false;
        }
        Identifier a = levelMaterial.id();
        Identifier b = worldMaterial.id();
        if (a == null || b == null) {
            return false; // guard: unidentified material -> never throttle
        }
        return a.equals(b);
    }
}
