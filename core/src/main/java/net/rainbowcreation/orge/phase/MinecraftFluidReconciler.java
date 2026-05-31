package net.rainbowcreation.orge.phase;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.scheduler.ThermalWorld;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * Live {@link FluidReconciler} over a running {@link MinecraftServer} (DESIGN §10 Decision 9).
 * After the scheduler writes a section's new masses, this maps each fluid cell's stored mass to a
 * vanilla {@code minecraft:water}/{@code lava} render LEVEL ({@link FluidReconcileLogic}): mass ≈ 0
 * removes the managed fluid block (→ air); otherwise it places the material's representative block
 * with the computed {@code LiquidBlock.LEVEL}. Writes use {@link Block#UPDATE_CLIENTS} only (no
 * neighbour/physics cascade), exactly like {@link MinecraftPhaseChanger}. Server-thread only;
 * mirrors that class's server-binding + section-read pattern and reuses {@link LiveMaterials}.
 *
 * <p>Cells already matching their target state are skipped to avoid churn. Mass is read from
 * {@link SectionData}; the level math is the already-tested {@link FluidReconcileLogic}.</p>
 */
public final class MinecraftFluidReconciler implements FluidReconciler {

    /** ORGE's one managed gas block id; hoisted out of the per-cell reconcile loop (no per-cell alloc). */
    private static final Identifier ORGE_STEAM = Identifier.fromNamespaceAndPath("orge", "steam");

    private final SectionStoreManager stores;
    private volatile MinecraftServer server;

    public MinecraftFluidReconciler(SectionStoreManager stores) {
        this.stores = stores;
    }

    /** Bind the running server (on SERVER_STARTED); unbind on stop. */
    public void bindServer(MinecraftServer server) { this.server = server; }
    public void unbindServer() { this.server = null; }

    @Override
    public void reconcile(ThermalWorld.BatchEntry entry) {
        reconcile(entry, null, null);
    }

    @Override
    public void reconcile(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut) {
        reconcile(entry.dimension(), entry.key(), outMaterial, outLut);
    }

    @Override
    public void reconcile(Identifier dim, SubchunkKey key, char[] outMaterial, List<Material> outLut) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return;
        }
        ServerLevel level = levelFor(srv, dim);
        if (level == null) {
            return;
        }
        LevelChunk chunk = LiveMaterials.loadedChunk(level, key.cx(), key.cz());
        if (chunk == null) {
            return; // unloaded since the snapshot
        }
        LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, key.sectionY());
        if (section == null) {
            return;
        }
        SectionStore store = stores.store(dim);
        if (store == null || !store.isLoaded(key.cx(), key.cz())) {
            return;
        }
        SectionData data = store.get(key);
        ActiveMaterials.State mats = ActiveMaterials.current();

        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;

        for (int i = 0; i < SectionData.CELLS; i++) {
            BlockState current = LiveMaterials.blockStateAt(section, i);
            Material worldMaterial = LiveMaterials.materialFor(current, mats);

            // The species the cell BECAME this step (engine matOut); fall back to the world block's
            // material when the engine didn't report one (null overload / non-advection cycle).
            Material outMat = outMaterialFor(outMaterial, outLut, i, worldMaterial);

            // Reconcile a cell when EITHER the world block is a managed fluid OR the engine says it is
            // now a fluid (wetting an air cell). Skip cells that are and stay non-fluid.
            boolean worldIsFluid = worldMaterial.fluid();
            boolean becameFluid = outMat != null && outMat.fluid();
            if (!worldIsFluid && !becameFluid) {
                continue;
            }

            float mass = data.massAt(i);
            // The cap/full reference is the species the cell now holds (so a wetted air cell reads
            // water's 1000 kg full reference, not air's).
            Material levelMaterial = becameFluid ? outMat : worldMaterial;
            float f = FluidReconcileLogic.fraction(mass, levelMaterial.defaultMass());
            int renderLevel = FluidReconcileLogic.levelForFraction(f);

            // ---- level-bucket throttle (Decision 13b) ----
            int currentBucket = bucketOfWorldBlock(current);
            if (FluidReconcileLogic.levelBucket(renderLevel) == currentBucket) {
                continue; // mass moved within the same render bucket -> no packet
            }

            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockPos pos = new BlockPos(ox + x, oy + y, oz + z);

            if (renderLevel == FluidReconcileLogic.REMOVE) {
                // Only clear a cell that currently holds a managed fluid block; leave others alone.
                if (current.getBlock() instanceof LiquidBlock || isManagedGas(current)) {
                    setIfChanged(level, pos, current, Blocks.AIR.defaultBlockState());
                }
                continue;
            }

            // §7 contact whitelist: do not overwrite a block that §7 owns (water+lava→obsidian etc.).
            // The whitelist is "only place over air or over a managed fluid/gas block"; never stomp a
            // solid the phase-changer produced.
            if (!isPlaceableTarget(current, levelMaterial)) {
                continue;
            }

            Block target = representativeBlock(levelMaterial);
            if (target == null) {
                continue; // material has no representative block to place
            }
            setIfChanged(level, pos, current, stateWithLevel(target, renderLevel));
        }
    }

    /**
     * Places {@code desired} at {@code pos} with {@link Block#UPDATE_CLIENTS} only (no
     * neighbour/physics cascade, DESIGN §7/§10), skipping the write when it already matches.
     */
    private static void setIfChanged(ServerLevel level, BlockPos pos,
                                     BlockState current, BlockState desired) {
        if (!current.equals(desired)) {
            level.setBlock(pos, desired, Block.UPDATE_CLIENTS);
        }
    }

    /** The fluid block this material renders as, or null when it declares no representative block. */
    private static Block representativeBlock(Material material) {
        Identifier id = material.representativeBlock();
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    /**
     * The engine's output species for cell {@code i}, resolved through the step's batch LUT
     * ({@code outLut}, threaded in from the scheduler's {@code pendingMaterials}). Falls back to
     * {@code worldMaterial} when no engine species is available or the cell is the VOID/air sentinel
     * (index 0).
     */
    private static Material outMaterialFor(char[] outMaterial, List<Material> outLut, int i,
                                           Material worldMaterial) {
        if (outMaterial == null || outLut == null || i >= outMaterial.length) {
            return worldMaterial;
        }
        int s = outMaterial[i];
        if (s == 0 || s >= outLut.size()) {
            return worldMaterial; // air cell that received no fluid mass; world-block path decides
        }
        return outLut.get(s);
    }

    /** The render bucket the world block currently shows: REMOVE for non-fluid, else its LEVEL. */
    private static int bucketOfWorldBlock(BlockState current) {
        if (current.getBlock() instanceof LiquidBlock && current.hasProperty(LiquidBlock.LEVEL)) {
            return FluidReconcileLogic.levelBucket(current.getValue(LiquidBlock.LEVEL));
        }
        if (isManagedGas(current)) {
            return FluidReconcileLogic.levelBucket(0); // gas renders as a single full bucket
        }
        return FluidReconcileLogic.levelBucket(FluidReconcileLogic.REMOVE);
    }

    /** True when {@code current} is ORGE's managed gas block ({@code orge:steam}). */
    private static boolean isManagedGas(BlockState current) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(current.getBlock());
        return id != null && id.equals(ORGE_STEAM);
    }

    /**
     * §7 contact whitelist (Decision 7): a cell is placeable only when it is currently air/replaceable
     * OR already the managed fluid/gas block. This refuses to overwrite a solid (e.g. obsidian/stone
     * the phase-changer produced from a water+lava contact), leaving §7 in charge.
     */
    private static boolean isPlaceableTarget(BlockState current, Material material) {
        if (current.isAir()) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock || isManagedGas(current)) {
            return true; // already a managed fluid/gas; updating its level is fine
        }
        return false; // solid or other block -> §7 / vanilla owns it, do not stomp
    }

    /** {@code block}'s default state carrying {@code level} on {@link LiquidBlock#LEVEL} (clamped). */
    private static BlockState stateWithLevel(Block block, int level) {
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(LiquidBlock.LEVEL)) {
            int min = 0;
            int max = LiquidBlock.LEVEL.getPossibleValues().size() - 1;
            int clamped = Math.max(min, Math.min(level, max));
            state = state.setValue(LiquidBlock.LEVEL, clamped);
        }
        return state;
    }

    private static ServerLevel levelFor(MinecraftServer srv, Identifier dimension) {
        for (ServerLevel level : srv.getAllLevels()) {
            if (level.dimension().identifier().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
