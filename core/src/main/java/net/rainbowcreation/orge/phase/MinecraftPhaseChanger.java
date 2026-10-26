package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        SectionStore store = liveStore(entry);
        if (store == null) {
            return;
        }
        LevelChunkSection section = liveSection(entry);
        ActiveMaterials.State mats = ActiveMaterials.current();
        IntFunction<Material> cellMat = i -> EngineOutSpecies.resolve(outMaterial, outLut, i,
                LiveMaterials.materialFor(LiveMaterials.blockAt(section, i), mats.registry()));
        List<SourcePinPlanner.Reset> resets = SourcePinPlanner.plan(cellMat);
        if (resets.isEmpty()) {
            return;
        }
        pinSources(store, entry.key(), resets, id -> mats.registry().get(id).orElse(null));
    }

    /**
     * Plans the re-pin off-thread from the captured blocks (issue #51): the same per-cell species as the
     * live pass — the engine out-species, else the captured block's first-touch material — so applying it
     * only writes the planned sources' E. A section whose blocks changed after the capture re-plans live.
     */
    @Override
    public Plan plan(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut,
                     IntFunction<BlockState> captured) {
        ActiveMaterials.State mats = ActiveMaterials.current();
        Map<Block, Material> firstTouch = new HashMap<>();
        List<SourcePinPlanner.Reset> resets = SourcePinPlanner.plan(i -> {
            Material out = EngineOutSpecies.resolve(outMaterial, outLut, i, null);
            return out != null ? out : firstTouch.computeIfAbsent(captured.apply(i).getBlock(),
                    b -> LiveMaterials.materialFor(b, mats.registry()));
        });
        return changedSinceCapture -> {
            if (changedSinceCapture) {
                applyPhaseChanges(entry, outMaterial, outLut);
                return;
            }
            SectionStore store = resets.isEmpty() ? null : liveStore(entry);
            if (store != null) {
                pinSources(store, entry.key(), resets, id -> mats.registry().get(id).orElse(null));
            }
        };
    }

    /** The section's live chunk section, or null when the server, level, chunk or section is gone. */
    private LevelChunkSection liveSection(ThermalWorld.BatchEntry entry) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return null;
        }
        ServerLevel level = levelFor(srv, entry.dimension());
        if (level == null) {
            return null;
        }
        SubchunkKey key = entry.key();
        LevelChunk chunk = LiveMaterials.loadedChunk(level, key.cx(), key.cz());
        if (chunk == null) {
            return null; // unloaded since the snapshot
        }
        return LiveMaterials.sectionOrNull(chunk, key.sectionY());
    }

    /** The section's loaded store when its live section is still there, else null (nothing to pin). */
    private SectionStore liveStore(ThermalWorld.BatchEntry entry) {
        if (liveSection(entry) == null) {
            return null;
        }
        SectionStore store = stores.store(entry.dimension());
        SubchunkKey key = entry.key();
        return store != null && store.isLoaded(key.cx(), key.cz()) ? store : null;
    }

    /**
     * Hold each surviving source at its default_temperature, stored as extensive E = mass·h(T) via the
     * cell's STORED species curve; an unresolvable / massless cell stores 0 J. Persists the section (a
     * never-stored one materializes as ambient here). Public so the headless suite drives the exact
     * pinned-source write without a running server.
     */
    public static void pinSources(SectionStore store, SubchunkKey key, List<SourcePinPlanner.Reset> resets,
                                  Function<Identifier, Material> lookup) {
        SectionData data = store.get(key);
        for (SourcePinPlanner.Reset r : resets) {
            int ci = r.cellIndex();
            Material cellM = lookup.apply(data.materialAt(ci));
            float massKg = data.massAt(ci);
            double e = (cellM == null || massKg <= 0f)
                    ? 0.0 : EnthalpyCurve.cellE(massKg, cellM, lookup, r.temperatureK());
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
