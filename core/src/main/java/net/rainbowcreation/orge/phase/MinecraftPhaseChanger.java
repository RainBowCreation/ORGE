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
import java.util.Optional;
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

        // Read temps + mass without forcing a UNIFORM→FULL promotion (the *At accessors work for
        // both forms). Mass gates the phase rule so drained/empty cells never transition (Bug B).
        float[] temps = new float[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            temps[i] = data.temperatureAt(i);
            mass[i] = data.massAt(i);
        }

        ActiveMaterials.State mats = ActiveMaterials.current();
        final LevelChunkSection sec = section;
        // Pair each cell with the species the engine says it BECAME this step (matOut), not the live
        // block: the native molar-sort swaps fluids vertically (lava sinks under water) and the
        // reconciler rewrites blocks only AFTER this changer runs, so the live block is still the
        // OUTGOING material. Reading it paired a swapped-in temperature with the wrong material —
        // risen water carried its cool temperature while the block read lava, so PhaseRule saw
        // cool < lava.minTemp and froze the water to lava.minTarget = stone. EngineOutSpecies falls
        // back to the live block when the engine reported no species (null args / vacuum sentinel), so
        // a surviving pinned source still reads as its source material for the re-pin below.
        IntFunction<Material> cellMat = i -> EngineOutSpecies.resolve(
                outMaterial, outLut, i, LiveMaterials.materialFor(LiveMaterials.blockAt(sec, i), mats.registry()));

        // The planner works in MATERIAL ids and the existence check is "does this target MATERIAL
        // exist?" — the block to draw is the separate material → representative_block lookup below.
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(
                temps, mass, cellMat, id -> mats.registry().get(id).isPresent());
        List<SourcePinPlanner.Reset> resets = SourcePinPlanner.plan(cellMat, plan);

        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;
        for (PhasePlanner.Transition t : plan) {
            // Resolve the target MATERIAL → its representative_block (a separate lookup; identity is
            // the material id, the block is only what's drawn). Skip if the material or its repr block
            // isn't available. BANKED: fully block-decoupled material identity (so invisible gases can
            // share minecraft:air as their repr) — v1 records the new species ONLY via the placed
            // representative_block, so each phase-target material must have a uniquely-bound repr block
            // (§8 geometry rescan reads it back through the first-touch BlockMaterialRule).
            Optional<Identifier> repr =
                    PhaseRenderResolver.representativeBlock(mats.registry()::get, t.materialId());
            if (repr.isEmpty()) {
                continue; // material unregistered → nothing to draw
            }
            // Nonexistent repr block downgrades to minecraft:air (BLOCK is a defaulted registry whose
            // getValue returns AIR for unknown ids), matching the material schema's air-fallback.
            int i = t.cellIndex();
            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockState state = BuiltInRegistries.BLOCK.getValue(repr.get()).defaultBlockState();
            // UPDATE_CLIENTS only: sync the change to clients but skip the neighbour/physics
            // cascade (DESIGN §7). The chunk light engine still re-lights on the state change;
            // if a light-emitting transition (e.g. lava→stone) ever looks stale, revisit the flag.
            level.setBlock(new BlockPos(ox + x, oy + y, oz + z), state, Block.UPDATE_CLIENTS);
        }

        // Conditional re-pin: hold surviving source cells at their default_temperature (engine-audit C).
        if (!resets.isEmpty()) {
            for (SourcePinPlanner.Reset r : resets) {
                data.setTemperature(r.cellIndex(), r.temperatureK());
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
