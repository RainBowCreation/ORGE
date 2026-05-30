package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.rainbowcreation.orge.Orge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads materials and block→material bindings from datapack JSON, reloadable via
 * {@code /reload} (DESIGN.md §6):
 *
 * <pre>
 *   data/&lt;ns&gt;/orge/materials/&lt;path...&gt;.json   — constant property set, id = Identifier(ns, path)
 *   data/&lt;ns&gt;/orge/bindings/&lt;name&gt;.json        — tag bindings + per-block overrides
 * </pre>
 *
 * <p>This is the primary registration path; a thin Java API is the secondary one.</p>
 *
 * <p>Implemented as a {@link SimplePreparableReloadListener}: {@link #prepare} scans
 * the {@link ResourceManager} and builds a <em>fresh</em> {@link ActiveMaterials.State}
 * off-thread (so a malformed pack throws here, before anything is published);
 * {@link #apply} then atomically swaps that fresh state into {@link ActiveMaterials}.
 * A failed reload therefore never corrupts the currently-active state.</p>
 *
 * <p>Registered for {@link net.minecraft.server.packs.PackType#SERVER_DATA} on both
 * loaders via Architectury's {@code ReloadListenerRegistry} in {@link Orge#init()}.</p>
 */
public final class MaterialJsonLoader extends SimplePreparableReloadListener<ActiveMaterials.State> {

    private static final Logger LOGGER = LoggerFactory.getLogger("ORGE/materials");

    /** {@code data/<ns>/orge/materials/<path>.json} → id {@code Identifier(ns, path)}. */
    private static final FileToIdConverter MATERIALS = FileToIdConverter.json("orge/materials");
    /** {@code data/<ns>/orge/bindings/<name>.json}. */
    private static final FileToIdConverter BINDINGS = FileToIdConverter.json("orge/bindings");

    /**
     * Off-thread: scan the resource manager, parse all material + binding JSON, and
     * build a fresh state. Any parse/decode failure throws here (the reload fails
     * loudly) and the active state is never touched.
     */
    @Override
    protected ActiveMaterials.State prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<Identifier, JsonElement> materials = readMaterials(resourceManager);
        List<JsonElement> bindings = readBindings(resourceManager);
        ActiveMaterials.State state = ActiveMaterials.buildState(materials, bindings);
        LOGGER.info("ORGE materials prepared: {} materials, {} binding file(s)",
                materials.size(), bindings.size());
        return state;
    }

    /** Main thread: publish the freshly-built state atomically. */
    @Override
    protected void apply(ActiveMaterials.State state, ResourceManager resourceManager, ProfilerFiller profiler) {
        ActiveMaterials.swap(state);
        LOGGER.info("ORGE materials applied: {} material(s) now active",
                state.registry().all().size());
    }

    @Override
    public String getName() {
        return Orge.MOD_ID + ":materials";
    }

    // -------------------------------------------------------------------------
    // Resource scanning
    // -------------------------------------------------------------------------

    private static Map<Identifier, JsonElement> readMaterials(ResourceManager rm) {
        // Preserve a stable iteration order for deterministic logging/errors.
        Map<Identifier, JsonElement> out = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : MATERIALS.listMatchingResources(rm).entrySet()) {
            // fileToId strips the "orge/materials/" prefix and ".json" suffix, so the
            // resulting id is Identifier(namespace, path-without-extension), exactly the
            // material id we want.
            Identifier id = MATERIALS.fileToId(entry.getKey());
            out.put(id, parse(entry.getValue(), entry.getKey()));
        }
        return out;
    }

    private static List<JsonElement> readBindings(ResourceManager rm) {
        List<JsonElement> out = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : BINDINGS.listMatchingResources(rm).entrySet()) {
            out.add(parse(entry.getValue(), entry.getKey()));
        }
        return out;
    }

    private static JsonElement parse(Resource resource, Identifier file) {
        try (BufferedReader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("failed to read ORGE resource " + file + ": " + e.toString(), e);
        }
    }

}
