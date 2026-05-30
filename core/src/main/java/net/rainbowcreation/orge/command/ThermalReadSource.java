package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Optional;

/**
 * One link in the read chain. {@code OrgeCommandLogic} tries sources in order and uses the
 * first present result. v1 has only the server-store source; a future client-cache source is
 * prepended so reads prefer the freshest client-computed data and fall back to the server.
 */
public interface ThermalReadSource {

    /**
     * The section for {@code (dimension, key)} if this source can serve it; {@code empty}
     * means "I don't have it, try the next source." The server-store source returns empty
     * only when the dimension has no store at all (otherwise it returns the ambient baseline).
     */
    Optional<SectionView> section(Identifier dimension, SubchunkKey key);
}
