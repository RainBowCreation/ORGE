package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-batch material lookup table passed to {@link
 * net.rainbowcreation.orge.engine.OrgeEngine#step}. Index 0 is always the
 * {@link #VOID} sentinel — a material with {@code thermalConductivity = 0}, so the
 * engine's {@code k <= 0} skip makes void/edge cells inert (matches §2's tests).
 * Real materials are appended in first-seen order and keyed by id, so a repeated
 * material reuses its index. Rebuilt fresh each step; no cross-tick stability needed.
 */
public final class MaterialLut {

    /** The index-0 inert sentinel: conductivity 0 ⇒ no flux across it. */
    public static final Material VOID = new Material(
            Identifier.fromNamespaceAndPath("orge", "void"),
            0f,     // thermalConductivity — inert
            1f,     // heatCapacity (never divided by: void cells are never stepped)
            0f, 0f, 0f,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);

    private final List<Material> lut = new ArrayList<>();
    private final Map<Identifier, Character> byId = new HashMap<>();

    public MaterialLut() {
        lut.add(VOID);
        byId.put(VOID.id(), (char) 0);
    }

    /** Returns the LUT index for {@code material}, appending it on first sight. */
    public char indexOf(Material material) {
        Character existing = byId.get(material.id());
        if (existing != null) {
            return existing;
        }
        if (lut.size() > Character.MAX_VALUE) {
            throw new IllegalStateException("MaterialLut overflow: more than 65535 materials");
        }
        char ix = (char) lut.size();
        lut.add(material);
        byId.put(material.id(), ix);
        return ix;
    }

    /** The table to hand to the engine; index 0 = {@link #VOID}. */
    public List<Material> materials() {
        return Collections.unmodifiableList(lut);
    }
}
