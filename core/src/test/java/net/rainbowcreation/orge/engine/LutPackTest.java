package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.MaterialLut;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Task 2.1/10: LutArrays packs EXACTLY the seven physics floats; absent viscosity → +∞; void slot 0 = 0/0/0/finite. */
class LutPackTest {

    /** Live water built via the canonical builder: molar 0.018, minMass 125, maxMass 1000, visc 0.001. */
    private static Material water() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "water"))
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN)
                .viscosity(0.001f).minMass(125f).maxMass(1000f)
                .build();
    }

    /** A frozen material: viscosity ABSENT (builder leaves it +∞). */
    private static Material frozen() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "stone"))
                .thermalConductivity(1.0f).heatCapacity(840f).molarMass(0f)
                .defaultMass(2000f).defaultTemperature(Float.NaN)
                .build(); // no viscosity() => +∞
    }

    @Test
    void recordHasExactlySevenFloatArraysPlusMatCount() {
        RecordComponent[] comps = LutArrays.class.getRecordComponents();
        Set<String> names = Arrays.stream(comps).map(RecordComponent::getName).collect(Collectors.toSet());
        // Exactly the seven physics floats + matCount.
        assertEquals(Set.of("cond", "heatCap", "molar", "minMass", "maxMass", "visc", "defaultMass", "matCount"), names);
        // The dropped flag/legacy arrays must not exist.
        for (String banned : List.of("fluid", "gas", "air", "fullMass", "minFlow")) {
            assertFalse(names.contains(banned), "LutArrays must not carry '" + banned + "'");
        }
        // Seven float[] components, one int component.
        long floats = Arrays.stream(comps).filter(c -> c.getType() == float[].class).count();
        long ints = Arrays.stream(comps).filter(c -> c.getType() == int.class).count();
        assertEquals(7, floats, "exactly seven per-material float arrays");
        assertEquals(1, ints, "matCount");
    }

    @Test
    void packEmitsTheSevenArraysForANormalMaterial() {
        LutArrays L = LutArrays.pack(List.of(MaterialLut.VACUUM, water()));
        assertEquals(2, L.matCount());
        // water at slot 1 — the exact seven values.
        assertEquals(0.6f,   L.cond()[1],        1e-6f);
        assertEquals(4186f,  L.heatCap()[1],     1e-3f);
        assertEquals(0.018f, L.molar()[1],        1e-6f);
        assertEquals(125f,   L.minMass()[1],      1e-4f);
        assertEquals(1000f,  L.maxMass()[1],      1e-4f);
        assertEquals(0.001f, L.visc()[1],         1e-6f);
        assertEquals(1000f,  L.defaultMass()[1],  1e-4f);
    }

    @Test
    void absentViscosityPacksPositiveInfinity() {
        LutArrays L = LutArrays.pack(List.of(MaterialLut.VACUUM, frozen()));
        assertEquals(Float.POSITIVE_INFINITY, L.visc()[1], "absent viscosity → +∞ (frozen)");
        assertFalse(Float.isFinite(L.visc()[1]));
    }

    @Test
    void voidSlotZeroIsZeroZeroZeroFiniteVisc() {
        LutArrays L = LutArrays.pack(List.of(MaterialLut.VACUUM, water()));
        assertEquals(0f, L.molar()[0],   0f, "void molar 0");
        assertEquals(0f, L.minMass()[0], 0f, "void minMass 0");
        assertEquals(0f, L.maxMass()[0], 0f, "void maxMass 0");
        assertTrue(Float.isFinite(L.visc()[0]), "void must be a FINITE (displaceable, not frozen) fluid");
    }
}
