package net.rainbowcreation.orge.phase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import org.slf4j.Logger;

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
import net.rainbowcreation.orge.material.MaterialEffects;
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

    private static final Logger LOG = LogUtils.getLogger();
    /** Most effects one section sends per step (a boiling lake stays a few packets per section). */
    static final int MAX_FX_PER_SECTION = 16;

    private final SectionStoreManager stores;
    private volatile MinecraftServer server;
    /** Decoded particle per effect JSON (server thread only); empty = undecodable, warned once. */
    private final Map<JsonElement, Optional<ParticleOptions>> particles = new HashMap<>();

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
        reconcile(entry.dimension(), entry.key(), entry.task().matIx(), entry.task().mass(), outMaterial, outLut);
    }

    private void reconcile(Identifier dim, SubchunkKey key, char[] inMaterial, float[] inMass,
                           char[] outMaterial, List<Material> outLut) {
        Live live = live(dim, key);
        if (live == null) {
            return;
        }
        SectionData data = live.store().get(key);
        Lookups lookups = new Lookups(ActiveMaterials.current());
        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;
        for (int i = 0; i < SectionData.CELLS; i++) {
            BlockState current = LiveMaterials.blockStateAt(live.section(), i);
            BlockState desired = desiredState(current, i, data, inMaterial, inMass, outMaterial, outLut, lookups);
            if (desired != null) {
                setIfChanged(live.level(), posOf(ox, oy, oz, i), current, desired);
            }
        }
    }

    /** One planned block write: cell {@code i} showed {@code expected} at capture and is to show {@code desired}. */
    private record Write(int cell, BlockState expected, BlockState desired) {}

    /**
     * Plans the section's writes off-thread (issue #51): the live pass's per-cell decision, made on the
     * captured block states and the section the write-back installs. Applying it re-reads each planned
     * cell; if one no longer shows what the plan saw — or a block of the section changed after the capture
     * — the section is reconciled live instead, so a stale plan never paints.
     */
    @Override
    public Plan plan(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut,
                     SectionData after, IntFunction<BlockState> captured) {
        Lookups lookups = new Lookups(ActiveMaterials.current());
        char[] inMaterial = entry.task().matIx();
        float[] inMass = entry.task().mass();
        List<Write> writes = new ArrayList<>();
        List<Fx> fx = new ArrayList<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            BlockState current = captured.apply(i);
            Material worldMaterial = lookups.firstTouch(current.getBlock());
            Material outMat = EngineOutSpecies.resolve(outMaterial, outLut, i, worldMaterial);
            List<RepresentativeState.Component> comps = components(after, i, outMat, lookups);
            BlockState desired = desiredState(current, i, after, inMaterial, inMass, outMaterial, outLut, lookups,
                    worldMaterial, outMat, comps);
            if (desired != null && !current.equals(desired)) {
                writes.add(new Write(i, current, desired));
            }
            if (fx.size() < MAX_FX_PER_SECTION && hasEffects(comps)) {
                BlockState shown = desired != null ? desired : current;
                int above = i + 16;   // +y within the section; the top layer has no captured block above
                boolean solidAbove = ((i >> 4) & 15) < 15 && isSolid(captured.apply(above));
                for (CellEffects.Emit e : CellEffects.pick(comps, cellT(after, i, comps, lookups),
                        shown.isAir(), solidAbove)) {
                    fx.add(new Fx(i, e));
                }
            }
        }
        return changedSinceCapture -> {
            emit(entry, fx);
            if (changedSinceCapture) {
                reconcile(entry, outMaterial, outLut);
                return;
            }
            if (writes.isEmpty()) {
                return;
            }
            SubchunkKey key = entry.key();
            Live live = live(entry.dimension(), key);
            if (live == null) {
                return;
            }
            int ox = key.cx() << 4;
            int oy = key.sectionY() << 4;
            int oz = key.cz() << 4;
            for (Write w : writes) {
                // Re-read right before painting: a planned cell that no longer shows what the plan saw (a change
                // since the capture, or an earlier paint's shape update) makes the rest of the plan stale.
                if (!LiveMaterials.blockStateAt(live.section(), w.cell()).equals(w.expected())) {
                    reconcile(entry, outMaterial, outLut);
                    return;
                }
                setIfChanged(live.level(), posOf(ox, oy, oz, w.cell()), w.expected(), w.desired());
            }
        };
    }

    /** The active material table's lookups for one section's pass, each resolved once per distinct key. */
    private static final class Lookups {
        private final ActiveMaterials.State mats;
        private final Map<Block, Material> firstTouch = new HashMap<>();
        private final Map<Identifier, Optional<Material>> byId = new HashMap<>();

        // Last key -> value: a section repeats the same block / palette id cell after cell, so the identity
        // check skips the hash (Identifier/String hashCode was the step thread's top self-time).
        private Block lastBlock;
        private Material lastFirstTouch;
        private Identifier lastId;
        private Material lastById;

        Lookups(ActiveMaterials.State mats) {
            this.mats = mats;
        }

        /** The block's first-touch material ({@link LiveMaterials#materialFor}). */
        Material firstTouch(Block block) {
            if (block != lastBlock) {
                lastFirstTouch = firstTouch.computeIfAbsent(block, b -> LiveMaterials.materialFor(b, mats.registry()));
                lastBlock = block;
            }
            return lastFirstTouch;
        }

        /** The registered material {@code id}, or null. */
        Material byId(Identifier id) {
            if (id != lastId || lastId == null) {
                lastById = byId.computeIfAbsent(id, k -> mats.registry().get(k)).orElse(null);
                lastId = id;
            }
            return lastById;
        }
    }

    /** The live level, chunk section and loaded store a reconcile writes through. */
    private record Live(ServerLevel level, LevelChunkSection section, SectionStore store) {}

    /** The section's live level/section/store, or null when any is gone (nothing to reconcile). */
    private Live live(Identifier dim, SubchunkKey key) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return null;
        }
        ServerLevel level = levelFor(srv, dim);
        if (level == null) {
            return null;
        }
        LevelChunk chunk = LiveMaterials.loadedChunk(level, key.cx(), key.cz());
        if (chunk == null) {
            return null; // unloaded since the snapshot
        }
        LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, key.sectionY());
        if (section == null) {
            return null;
        }
        SectionStore store = stores.store(dim);
        if (store == null || !store.isLoaded(key.cx(), key.cz())) {
            return null;
        }
        return new Live(level, section, store);
    }

    /**
     * The block cell {@code i} should show, given it shows {@code current} and holds what {@code data}
     * says after this step, or null to leave it alone. Pure over its arguments (no world access).
     */
    private static BlockState desiredState(BlockState current, int i, SectionData data, char[] inMaterial,
                                           float[] inMass, char[] outMaterial, List<Material> outLut,
                                           Lookups lookups) {
        Material worldMaterial = lookups.firstTouch(current.getBlock());

        // The species the cell BECAME this step (engine matOut), shared with the §7 changer; falls
        // back to the world block's material when the engine didn't report one.
        Material outMat = EngineOutSpecies.resolve(outMaterial, outLut, i, worldMaterial);
        return desiredState(current, i, data, inMaterial, inMass, outMaterial, outLut, lookups, worldMaterial,
                outMat, components(data, i, outMat, lookups));
    }

    private static BlockState desiredState(BlockState current, int i, SectionData data, char[] inMaterial,
                                           float[] inMass, char[] outMaterial, List<Material> outLut,
                                           Lookups lookups, Material worldMaterial, Material outMat,
                                           List<RepresentativeState.Component> comps) {

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
        if (RepresentativeState.hostPresent(worldMaterial, comps)) {
            if (current.hasProperty(BlockStateProperties.WATERLOGGED)) {
                Boolean wl = RepresentativeState.waterlogged(worldMaterial, comps,
                        current.getValue(BlockStateProperties.WATERLOGGED));
                if (wl != null) {
                    return current.setValue(BlockStateProperties.WATERLOGGED, wl);
                }
            }
            return null;
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
                worldShowsSpecies(current, worldMaterial, inMat)
                        || FluidReconcileDecider.tracedAsAir(current.isAir(), inMat,
                                inMass != null ? inMass[i] : Float.POSITIVE_INFINITY),
                inMat != null && outMaterial != null && outMaterial[i] == 0);
        if (action.kind() == FluidReconcileDecider.Kind.SKIP) {
            return null;
        }
        if (action.kind() == FluidReconcileDecider.Kind.CLEAR) {
            // Decider confirmed a managed fluid block, or a solid the engine drained; remove it (→ air).
            return Blocks.AIR.defaultBlockState();
        }
        // PLACE: a nonexistent repr block downgrades to minecraft:air (BLOCK getValue returns AIR
        // for unknown ids); the decider already dropped a material with no repr block at all.
        Block target = BuiltInRegistries.BLOCK.getValue(action.block());
        return stateWithLevel(target, action.renderLevel());
    }

    /** One effect planned for cell {@code cell}. */
    private record Fx(int cell, CellEffects.Emit emit) {}

    private static boolean hasEffects(List<RepresentativeState.Component> comps) {
        for (RepresentativeState.Component c : comps) {
            if (c.material().effects() != MaterialEffects.NONE) return true;
        }
        return false;
    }

    private static boolean isSolid(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty();
    }

    /** The cell's temperature from its stored enthalpy and composition (the engine's decode). */
    private static float cellT(SectionData data, int i, List<RepresentativeState.Component> comps, Lookups lookups) {
        int n = comps.size();
        Material[] mats = new Material[n];
        float[] masses = new float[n];
        for (int k = 0; k < n; k++) {
            mats[k] = comps.get(k).material();
            masses[k] = comps.get(k).massKg();
        }
        return EnthalpyCurve.mixtureT(data.enthalpyAt(i), mats, masses, n, lookups::byId, Float.NaN);
    }

    /** Sends the planned effects (server thread): each particle with its chance, its sound with the sound's. */
    private void emit(ThermalWorld.BatchEntry entry, List<Fx> fx) {
        if (fx.isEmpty()) {
            return;
        }
        MinecraftServer srv = this.server;
        ServerLevel level = srv == null ? null : levelFor(srv, entry.dimension());
        if (level == null) {
            return;
        }
        RandomSource r = level.getRandom();
        SubchunkKey key = entry.key();
        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;
        for (Fx f : fx) {
            CellEffects.Emit e = f.emit();
            if (r.nextFloat() >= e.chance()) continue;
            ParticleOptions p = particle(level, e.particle());
            BlockPos pos = posOf(ox, oy, oz, f.cell());
            double y = pos.getY() + (e.atTop() ? 0.95 : 0.5);   // a drip hangs at the ceiling
            if (p != null) {
                level.sendParticles(p, pos.getX() + 0.5, y, pos.getZ() + 0.5, 1,
                        0.3, e.atTop() ? 0.0 : 0.3, 0.3, 0.0);
            }
            Identifier sound = e.effect().sound();
            if (sound != null && r.nextFloat() < e.effect().soundChance()) {
                SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getOptional(sound)
                        .orElseGet(() -> SoundEvent.createVariableRangeEvent(sound));
                level.playSound(null, pos, ev, SoundSource.BLOCKS, 0.4f, 0.9f + 0.2f * r.nextFloat());
            }
        }
    }

    private ParticleOptions particle(ServerLevel level, JsonElement json) {
        return particles.computeIfAbsent(json, j -> ParticleTypes.CODEC
                .parse(level.registryAccess().createSerializationContext(JsonOps.INSTANCE), j)
                .resultOrPartial(err -> LOG.warn("ORGE: material effect particle {} unreadable: {}", j, err)))
                .orElse(null);
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
                                                                  Lookups lookups) {
        List<RepresentativeState.Component> comps = new ArrayList<>(SectionData.SLOTS);
        Material m0 = data.hasMaterials() ? lookups.byId(data.materialAt(i)) : slot0;
        if (m0 != null && data.massAt(i) > 0f) {
            comps.add(new RepresentativeState.Component(m0, data.massAt(i)));
        }
        if (data.hasSlots()) {
            for (int k = 1; k < SectionData.SLOTS; k++) {
                float mk = data.slotMassAt(k, i);
                if (!(mk > 0f)) continue;
                Material mat = lookups.byId(data.slotMaterialAt(k, i));
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
