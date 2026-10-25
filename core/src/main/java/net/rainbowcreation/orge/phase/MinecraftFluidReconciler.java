package net.rainbowcreation.orge.phase;

import java.util.ArrayList;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
 * neighbour/physics cascade), raised under {@link LiveMaterials#selfWrite} so the placement capture never
 * reads them as a player edit. Server-thread only;
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
        reconcile(entry.dimension(), entry.key(), entry.task().matIx(), outMaterial, outLut);
    }

    private void reconcile(Identifier dim, SubchunkKey key, char[] inMaterial,
                           char[] outMaterial, List<Material> outLut) {
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
            // A fluid whose own block isn't a LiquidBlock (honey renders as honey_block) is still the
            // managed fluid block here, so it can be cleared when drained. Kelp (aliased to water) is not
            // water's own block, so it is never stomped.
            boolean currentIsLiquid = current.getBlock() instanceof LiquidBlock
                    || (worldMaterial != null && worldMaterial.movable() && !current.isAir()
                        && current.getBlock() == BuiltInRegistries.BLOCK.getValue(worldMaterial.representativeBlock()));
            // Stale-write guard for the species paint: this step's INPUT species (null = vacuum/unknown).
            Material inMat = EngineOutSpecies.resolve(inMaterial, outLut, i, null);

            // DESIGN-LAW v5 #7 (proposal §6): the cell is a composition. Slot order is the LUT order, not a
            // role, so slot 0 can be the air or water a stair/rail/soil holds. A host the live block still
            // shows is KEPT — only its waterlogged property follows the water it holds; a host-absent
            // composition is drawn as its largest condensed component.
            List<RepresentativeState.Component> comps = components(data, i, outMat, mats);
            if (RepresentativeState.hostPresent(worldMaterial, comps)) {
                if (current.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    Boolean wl = RepresentativeState.waterlogged(worldMaterial, comps,
                            current.getValue(BlockStateProperties.WATERLOGGED));
                    if (wl != null) {
                        setIfChanged(level, posOf(ox, oy, oz, i), current,
                                current.setValue(BlockStateProperties.WATERLOGGED, wl));
                    }
                }
                continue;
            }
            float massKg = data.massAt(i);
            if (comps.size() > 1) {
                RepresentativeState.Component dom = RepresentativeState.dominant(comps);
                outMat = dom.material();
                massKg = dom.massKg();
            }
            FluidReconcileDecider.Action action = FluidReconcileDecider.decide(
                    worldMaterial, outMat, massKg,
                    bucketOfWorldBlock(current), currentIsLiquid, current.isAir(),
                    worldShowsSpecies(current, worldMaterial, inMat),
                    inMat != null && outMaterial != null && outMaterial[i] == 0);
            if (action.kind() == FluidReconcileDecider.Kind.SKIP) {
                continue;
            }

            BlockPos pos = posOf(ox, oy, oz, i);

            if (action.kind() == FluidReconcileDecider.Kind.CLEAR) {
                // Decider confirmed a managed fluid block, or a solid the engine drained; remove it (→ air).
                setIfChanged(level, pos, current, Blocks.AIR.defaultBlockState());
                continue;
            }

            // PLACE: a nonexistent repr block downgrades to minecraft:air (BLOCK getValue returns AIR
            // for unknown ids); the decider already dropped a material with no repr block at all.
            Block target = BuiltInRegistries.BLOCK.getValue(action.block());
            setIfChanged(level, pos, current, stateWithLevel(target, action.renderLevel()));
        }
    }

    private static BlockPos posOf(int ox, int oy, int oz, int i) {
        return new BlockPos(ox + (i & 15), oy + ((i >> 4) & 15), oz + ((i >> 8) & 15));
    }

    /**
     * The cell's occupied composition slots after this step (slot 0 + the v5 #7 slots 1..K−1). Slot 0's
     * species is the stored material when the section carries a material layer, else the engine species.
     * A slot whose id is not registered is dropped (it cannot be drawn).
     */
    private static List<RepresentativeState.Component> components(SectionData data, int i, Material slot0,
                                                                  ActiveMaterials.State mats) {
        List<RepresentativeState.Component> comps = new ArrayList<>(SectionData.SLOTS);
        Material m0 = data.hasMaterials() ? mats.registry().get(data.materialAt(i)).orElse(null) : slot0;
        if (m0 != null && data.massAt(i) > 0f) {
            comps.add(new RepresentativeState.Component(m0, data.massAt(i)));
        }
        if (data.hasSlots()) {
            for (int k = 1; k < SectionData.SLOTS; k++) {
                float mk = data.slotMassAt(k, i);
                if (!(mk > 0f)) continue;
                Material mat = mats.registry().get(data.slotMaterialAt(k, i)).orElse(null);
                if (mat != null) {
                    comps.add(new RepresentativeState.Component(mat, mk));
                }
            }
        }
        return comps;
    }

    /**
     * Places {@code desired} at {@code pos} with {@link Block#UPDATE_CLIENTS} only (no
     * neighbour/physics cascade, DESIGN §7/§10), skipping the write when it already matches.
     */
    private static void setIfChanged(ServerLevel level, BlockPos pos,
                                     BlockState current, BlockState desired) {
        if (!current.equals(desired)) {
            LiveMaterials.selfWrite(() -> level.setBlock(pos, desired, Block.UPDATE_CLIENTS));
        }
    }

    /**
     * True when the live block still renders {@code species} — its first-touch material is that
     * species, or it IS that species' representative block (steam draws as air, whose first-touch is
     * orge:air). A null species (vacuum/unknown input) is shown by air. False means something outside
     * the engine (a player, a command) changed the block after this step's snapshot: the engine output
     * is stale there and must not be painted over it.
     */
    private static boolean worldShowsSpecies(BlockState current, Material worldMaterial, Material species) {
        if (species == null) {
            return current.isAir();
        }
        if (worldMaterial != null && species.id().equals(worldMaterial.id())) {
            return true;
        }
        Identifier repr = species.representativeBlock();
        return repr != null && current.getBlock() == BuiltInRegistries.BLOCK.getValue(repr);
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
