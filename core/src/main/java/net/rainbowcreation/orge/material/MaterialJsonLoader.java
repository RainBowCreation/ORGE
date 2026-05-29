package net.rainbowcreation.orge.material;

/**
 * Loads materials and block→material bindings from datapack JSON, reloadable via
 * {@code /reload} (DESIGN.md §6):
 *
 * <pre>
 *   data/&lt;ns&gt;/orge/materials/&lt;id&gt;.json      — constant property set
 *   data/&lt;ns&gt;/orge/bindings/&lt;name&gt;.json      — tag bindings + per-block overrides
 * </pre>
 *
 * <p>This is the primary registration path; a thin Java API is the secondary one.</p>
 */
public final class MaterialJsonLoader {

    private MaterialJsonLoader() {
    }

    // TODO(phase: materials): implement as a SimpleJsonResourceReloadListener (or the
    //  loader-agnostic Architectury equivalent) that parses materials/ and bindings/
    //  into the given registry + bindings, with a Codec for Material.
    public static void reload(MaterialRegistry registry, MaterialBindings bindings) {
        throw new UnsupportedOperationException("Phase 1 skeleton: material JSON loading not yet implemented");
    }
}
