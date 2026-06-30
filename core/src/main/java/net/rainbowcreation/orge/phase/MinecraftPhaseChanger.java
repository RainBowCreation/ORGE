package net.rainbowcreation.orge.phase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
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

import java.util.List;
import java.util.function.IntFunction;

/**
 * Live {@link PhaseChanger} over a running {@link MinecraftServer} (DESIGN §7). After the
 * scheduler writes a section's new temperatures, this evaluates each cell against its
 * material's boiling/freezing thresholds ({@link PhasePlanner}) and places the target block
 * with {@link Block#UPDATE_CLIENTS} (no neighbour/physics cascade). Server-thread only;
 * mirrors {@code MinecraftThermalWorld}'s server-binding pattern and reuses {@link LiveMaterials}.
 *
 * <p>Temperature is left untouched in {@link SectionData}, so it carries across the swap (v1
 * carries temperature only; mass is Phase 2). §8 rescans geometry every snapshot, so the new
 * block's material is picked up next tick with no invalidation plumbing.</p>
 */
public final class MinecraftPhaseChanger implements PhaseChanger {

    private final SectionStoreManager stores;
    private volatile MinecraftServer server;

    public MinecraftPhaseChanger(SectionStoreManager stores) {
        this.stores = stores;
    }

    /** Bind the running server (on SERVER_STARTED); unbind on stop. */
    public void bindServer(MinecraftServer server) { this.server = server; }
    public void unbindServer() { this.server = null; }

    @Override
    public void applyPhaseChanges(ThermalWorld.BatchEntry entry) {
        applyPhaseChanges(entry, null, null);
    }

    @Override
    public void applyPhaseChanges(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut) {
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
        final LevelChunkSection sec = section;
        // The only MC-typed input the decision needs: the live world block's material per cell, read
        // back through the first-touch rule. It is the fallback species when the engine reported none
        // (EngineOutSpecies handles the rest); the swap/temperature reasoning lives in the decider.
        IntFunction<Material> liveBlockMaterial =
                i -> LiveMaterials.materialFor(LiveMaterials.blockAt(sec, i), mats.registry());

        // Resolve the registry ONCE (id → Optional<Material>) and let the pure decider make every
        // load-bearing call: stored-species temperature, the phase plan, the conditional re-pin, and
        // the material → representative_block draw. The adapter is left with only the block-write and
        // the enthalpy-write below.
        PhaseChangeDecider.Plan plan = PhaseChangeDecider.plan(
                data, outMaterial, outLut, liveBlockMaterial,
                mats.registry()::get, SectionData.DEFAULT_AMBIENT_K);

        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;
        for (PhaseChangeDecider.Placement p : plan.placements()) {
            // The decider already resolved the target material → its representative_block. A
            // nonexistent repr block downgrades to minecraft:air (BLOCK is a defaulted registry whose
            // getValue returns AIR for unknown ids), matching the material schema's air-fallback.
            int i = p.cellIndex();
            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockState state = BuiltInRegistries.BLOCK.getValue(p.block()).defaultBlockState();
            // UPDATE_CLIENTS only: sync the change to clients but skip the neighbour/physics
            // cascade (DESIGN §7). The chunk light engine still re-lights on the state change;
            // if a light-emitting transition (e.g. lava→stone) ever looks stale, revisit the flag.
            level.setBlock(new BlockPos(ox + x, oy + y, oz + z), state, Block.UPDATE_CLIENTS);
        }

        // Apply the conditional re-pin (engine-audit C): the decider computed the Dirichlet hold as
        // stored extensive E (law §7 — T is never stored); we only write it back and persist.
        List<PhaseChangeDecider.EnthalpyWrite> writes = plan.enthalpyWrites();
        if (!writes.isEmpty()) {
            for (PhaseChangeDecider.EnthalpyWrite w : writes) {
                data.setEnthalpy(w.cellIndex(), w.enthalpyJ());
            }
            store.put(key, data);
        }
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
