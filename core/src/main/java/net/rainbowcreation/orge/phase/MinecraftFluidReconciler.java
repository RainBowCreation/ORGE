package net.rainbowcreation.orge.phase;

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
        MinecraftServer srv = this.server;
        if (srv == null) {
            return;
        }
        ServerLevel level = levelFor(srv, entry.dimension());
        if (level == null) {
            return;
        }
        SubchunkKey key = entry.key();
        LevelChunk chunk = LiveMaterials.loadedChunk(level, key.cx(), key.cz());
        if (chunk == null) {
            return; // unloaded since the snapshot
        }
        LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, key.sectionY());
        if (section == null) {
            return;
        }
        SectionStore store = stores.store(entry.dimension());
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
            Material material = LiveMaterials.materialFor(current, mats);
            if (!material.fluid()) {
                continue; // only fluid-bound cells are reconciled
            }
            float mass = data.massAt(i);
            float f = FluidReconcileLogic.fraction(mass, material.defaultMass());
            int renderLevel = FluidReconcileLogic.levelForFraction(f);

            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockPos pos = new BlockPos(ox + x, oy + y, oz + z);

            if (renderLevel == FluidReconcileLogic.REMOVE) {
                // Only clear a cell that currently holds a managed fluid block; leave others alone.
                if (current.getBlock() instanceof LiquidBlock) {
                    setIfChanged(level, pos, current, Blocks.AIR.defaultBlockState());
                }
                continue;
            }

            Block target = representativeBlock(material);
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
