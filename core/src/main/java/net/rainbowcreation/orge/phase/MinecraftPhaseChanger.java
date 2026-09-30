package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.scheduler.ThermalWorld;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Live {@link PhaseChanger} over a running {@link MinecraftServer}. The engine owns every phase
 * change (it relabels in DECODE, keeping mass and E — DESIGN-LAW #9 v4.4); Java never decides a
 * species and never places a block here — the {@link MinecraftFluidReconciler} paints whatever
 * species the engine reported. What remains is the pinned-source re-pin (engine-audit C): the engine
 * has no {@code pinned} concept, so every cell whose engine out-species is {@code pinned} is held at
 * its {@code default_temperature}, stored as extensive E (law §7 — T is never stored).
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
        IntFunction<Material> cellMat = i -> EngineOutSpecies.resolve(outMaterial, outLut, i,
                LiveMaterials.materialFor(LiveMaterials.blockAt(section, i), mats.registry()));
        List<SourcePinPlanner.Reset> resets = SourcePinPlanner.plan(cellMat);
        if (resets.isEmpty()) {
            return;
        }
        // Hold each surviving source at its default_temperature, stored as extensive E = mass·h(T) via
        // the cell's STORED species curve; an unresolvable / massless cell stores 0 J.
        Function<Identifier, Material> lookup = id -> mats.registry().get(id).orElse(null);
        for (SourcePinPlanner.Reset r : resets) {
            int ci = r.cellIndex();
            Material cellM = lookup.apply(data.materialAt(ci));
            float massKg = data.massAt(ci);
            float e = (cellM == null || massKg <= 0f)
                    ? 0f : (float) EnthalpyCurve.cellE(massKg, cellM, lookup, r.temperatureK());
            data.setEnthalpy(ci, e);
        }
        store.put(key, data);
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
