package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;
import java.util.Optional;

/**
 * A {@link CellStore} that walks an ordered chain of stores for READS — first present result wins — so
 * reads can prefer a fresher source (e.g. a future client cache) and fall back to the server. The LAST
 * store in the chain is the authoritative server store: all WRITES and the {@link #isLoaded} precondition
 * go there (writes are only ever server-authoritative). v1 production uses a single-element chain (the
 * {@link ServerCellStore}); this module exists so a client-cache source can be prepended without the
 * command layer changing. Server-thread only.
 */
public final class ReadChainCellStore implements CellStore {

    private final List<CellStore> chain;

    /** @param chain ordered read-preference list; must be non-empty, and its LAST element is the
     *               server-authoritative store that receives writes and answers {@link #isLoaded}. */
    public ReadChainCellStore(List<CellStore> chain) {
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("read chain must have at least the authoritative store");
        }
        this.chain = List.copyOf(chain);
    }

    @Override
    public Optional<SectionView> read(Identifier dimension, SubchunkKey key) {
        for (CellStore s : chain) {
            Optional<SectionView> v = s.read(dimension, key);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isLoaded(Identifier dimension, SubchunkKey key) {
        return authority().isLoaded(dimension, key);
    }

    @Override
    public void write(Identifier dimension, SubchunkKey key, int cell, float temperatureK, Float massKg) {
        authority().write(dimension, key, cell, temperatureK, massKg);
    }

    /** The server-authoritative store: the last link in the fallback chain. */
    private CellStore authority() {
        return chain.get(chain.size() - 1);
    }
}
