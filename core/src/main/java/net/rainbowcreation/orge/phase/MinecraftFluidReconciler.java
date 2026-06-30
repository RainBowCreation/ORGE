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

    private void reconcile(Identifier dim, SubchunkKey key, char[] outMaterial, List<Material> outLut) {
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
            Material worldMaterial = LiveMaterials.materialFor(current.getBlock(), mats.registry());

            // The species the cell BECAME this step (engine matOut), shared with the §7 changer; falls
            // back to the world block's material when the engine didn't report one.
            Material outMat = EngineOutSpecies.resolve(outMaterial, outLut, i, worldMaterial);

            // Resolve the cell's MC-typed facts once, then let the pure decider make every load-bearing
            // call (fluid test, level math, species-aware throttle, §7 whitelist, repr-block pick).
            boolean currentIsLiquid = current.getBlock() instanceof LiquidBlock;
            FluidReconcileDecider.Action action = FluidReconcileDecider.decide(
                    worldMaterial, outMat, data.massAt(i),
                    bucketOfWorldBlock(current), currentIsLiquid, current.isAir());
            if (action.kind() == FluidReconcileDecider.Kind.SKIP) {
                continue;
            }

            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockPos pos = new BlockPos(ox + x, oy + y, oz + z);

            if (action.kind() == FluidReconcileDecider.Kind.CLEAR) {
                // Decider already confirmed this cell holds a managed fluid block; remove it (→ air).
                setIfChanged(level, pos, current, Blocks.AIR.defaultBlockState());
                continue;
            }

            // PLACE: a nonexistent repr block downgrades to minecraft:air (BLOCK getValue returns AIR
            // for unknown ids); the decider already dropped a material with no repr block at all.
            Block target = BuiltInRegistries.BLOCK.getValue(action.block());
            setIfChanged(level, pos, current, stateWithLevel(target, action.renderLevel()));
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

    /** The render bucket the world block currently shows: REMOVE for non-fluid, else its LEVEL. */
    private static int bucketOfWorldBlock(BlockState current) {
        if (current.getBlock() instanceof LiquidBlock && current.hasProperty(LiquidBlock.LEVEL)) {
            return FluidReconcileLogic.levelBucket(current.getValue(LiquidBlock.LEVEL));
        }
        return FluidReconcileLogic.levelBucket(FluidReconcileLogic.REMOVE);
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
