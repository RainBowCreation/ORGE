package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-section material identity layer (DESIGN.md durable-material §): a small palette of distinct
 * {@link Identifier}s plus a {@code char[4096]} index array, mirroring how Minecraft stores
 * blockstates in a chunk section.
 *
 * <p>Slot 0 of the palette is <b>always</b> the {@code orge:vacuum} sentinel ({@link #VACUUM_ID}),
 * so a freshly-allocated palette (all indices {@code 0}) reads as vacuum everywhere until a cell is
 * written. New materials are appended to the palette on first sight (de-duplicated).</p>
 *
 * <p><b>Intentional first-seen, do NOT globalize.</b> This is the <i>on-disk</i> per-section palette
 * and its first-seen ordering is deliberate: the file format is {@link Identifier}-keyed, so the slot
 * numbers are private to each section and never cross the FFI boundary. Only the <i>engine-bound</i>
 * {@code matIx} encoding became globally stable (see {@code material.MaterialTable} and spec
 * {@code docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md}). Do not "fix"
 * this palette to use global ids.</p>
 */
public final class MaterialPalette {

    /** The reserved index-0 sentinel material id ({@code orge:vacuum}). */
    public static final Identifier VACUUM_ID = Identifier.fromNamespaceAndPath("orge", "vacuum");

    /** 16 × 16 × 16. */
    public static final int CELLS = SectionData.CELLS;

    private final List<Identifier> palette;
    private final Map<Identifier, Character> byId;
    private final char[] indices;

    /** A fresh palette seeded with {@code [VACUUM_ID]} and an all-zero (all-vacuum) index array. */
    public MaterialPalette() {
        this.palette = new ArrayList<>();
        this.byId = new HashMap<>();
        this.indices = new char[CELLS];
        appendVacuum();
    }

    /**
     * Adopts an existing palette + index array (used by the codec to reconstruct a section on load).
     * The supplied palette must have {@link #VACUUM_ID} at slot 0; the index array must be length
     * {@value CELLS}. Neither argument is copied.
     *
     * @param palette the palette list (slot 0 = vacuum)
     * @param indices length-{@value CELLS} index array
     */
    public MaterialPalette(List<Identifier> palette, char[] indices) {
        if (indices.length != CELLS) {
            throw new IllegalArgumentException(
                    "indices length must be " + CELLS + ", got " + indices.length);
        }
        if (palette.isEmpty() || !VACUUM_ID.equals(palette.get(0))) {
            throw new IllegalArgumentException("palette slot 0 must be " + VACUUM_ID);
        }
        this.palette = palette;
        this.indices = indices;
        this.byId = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            this.byId.put(palette.get(i), (char) i);
        }
    }

    private void appendVacuum() {
        palette.add(VACUUM_ID);
        byId.put(VACUUM_ID, (char) 0);
    }

    /**
     * Returns the palette slot for {@code id}, appending it on first sight. {@link #VACUUM_ID} is
     * always slot 0.
     */
    public int indexOf(Identifier id) {
        Character ix = byId.get(id);
        if (ix != null) {
            return ix;
        }
        int slot = palette.size();
        palette.add(id);
        byId.put(id, (char) slot);
        return slot;
    }

    /** Sets the material of cell {@code cell} (0..{@value CELLS}-1), interning {@code id}. */
    public void set(int cell, Identifier id) {
        indices[cell] = (char) indexOf(id);
    }

    /** The material of cell {@code cell} (0..{@value CELLS}-1). */
    public Identifier get(int cell) {
        return palette.get(indices[cell]);
    }

    /** The palette list (size includes the slot-0 vacuum sentinel). */
    public List<Identifier> palette() {
        return palette;
    }

    /**
     * The raw index array (length {@value CELLS}); live backing store.
     *
     * <p>Package-private: only {@link SectionData#snapshot()} reads it (to hand the palette to the
     * codec as a typed {@link SectionSnapshot} component). The codec never reaches the raw array
     * directly — this stays encapsulated within the section package.</p>
     */
    char[] indices() {
        return indices;
    }
}
