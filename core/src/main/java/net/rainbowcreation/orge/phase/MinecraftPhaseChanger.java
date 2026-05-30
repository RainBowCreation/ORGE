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
import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.scheduler.ThermalWorld;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;

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

        // Read temps without forcing a UNIFORM→FULL promotion (temperatureAt works for both forms).
        float[] temps = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            temps[i] = data.temperatureAt(i);
        }

        ActiveMaterials.State mats = ActiveMaterials.current();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(
                temps,
                i -> LiveMaterials.materialFor(LiveMaterials.blockAt(section, i), mats),
                BuiltInRegistries.BLOCK::containsKey);
        if (plan.isEmpty()) {
            return;
        }

        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;
        for (PhasePlanner.Transition t : plan) {
            int i = t.cellIndex();
            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockState state = BuiltInRegistries.BLOCK.getValue(t.blockId()).defaultBlockState();
            level.setBlock(new BlockPos(ox + x, oy + y, oz + z), state, Block.UPDATE_CLIENTS);
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
