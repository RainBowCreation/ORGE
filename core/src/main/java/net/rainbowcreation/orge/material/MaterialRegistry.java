package net.rainbowcreation.orge.material;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The active set of {@link Material}s, keyed by id (DESIGN.md §6).
 *
 * <p>Populated primarily from datapack JSON ({@link MaterialJsonLoader}) and
 * secondarily from a thin Java registration API, then frozen. A single global
 * fallback ({@code orge:generic_solid}) covers any block without a binding so the
 * ~1000+ block coverage goal needs no hand-authoring.</p>
 */
public final class MaterialRegistry {

    /** The id of the global fallback material (DESIGN.md §6). */
    public static final ResourceLocation FALLBACK_ID =
            ResourceLocation.fromNamespaceAndPath(net.rainbowcreation.orge.Orge.MOD_ID, "generic_solid");

    private final Map<ResourceLocation, Material> byId = new ConcurrentHashMap<>();

    /** Register or replace a material. Used by the JSON loader and the Java API. */
    public void put(Material material) {
        byId.put(material.id(), material);
    }

    public Optional<Material> get(ResourceLocation id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The material for {@code id}, or the global fallback if absent. */
    public Material getOrFallback(ResourceLocation id) {
        Material m = byId.get(id);
        if (m != null) {
            return m;
        }
        Material fallback = byId.get(FALLBACK_ID);
        if (fallback == null) {
            throw new IllegalStateException("Fallback material " + FALLBACK_ID + " not registered");
        }
        return fallback;
    }

    public Collection<Material> all() {
        return byId.values();
    }

    public void clear() {
        byId.clear();
    }

    // TODO(phase: materials): build the dense MaterialLUT (id -> uint16 index) the
    //  engine consumes, plus a stable index assignment cached per section version.
}
