package net.rainbowcreation.orge.section;

import java.nio.file.Path;

/**
 * The separate compressed region store under {@code world/orge/} (DESIGN.md §5).
 *
 * <p>Sections are grouped into region files {@code r.<x>.<z>.orge}, loaded/unloaded
 * alongside the corresponding chunk. The vanilla {@code .mca} files are never touched.
 * Each section is written as {@link SectionData.Form#UNIFORM} or
 * {@link SectionData.Form#FULL} ({@code deflate/zstd} of the two float arrays).</p>
 */
public final class RegionStore {

    /** Subdirectory of the world save that holds ORGE region files. */
    public static final String DIR_NAME = "orge";

    private final Path orgeDir;

    public RegionStore(Path worldDir) {
        this.orgeDir = worldDir.resolve(DIR_NAME);
    }

    public Path directory() {
        return orgeDir;
    }

    // TODO(phase: section-store): region-file format (header table + per-section
    //  UNIFORM/FULL payloads), load(SubchunkKey) and save(SubchunkKey, SectionData),
    //  and the chunk load/unload hooks that drive them.
    public SectionData load(SubchunkKey key) {
        throw new UnsupportedOperationException("Phase 1 skeleton: region store not yet implemented");
    }

    public void save(SubchunkKey key, SectionData data) {
        throw new UnsupportedOperationException("Phase 1 skeleton: region store not yet implemented");
    }
}
