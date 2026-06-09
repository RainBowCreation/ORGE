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

/**
 * Issue #2: LutArrays packs EXACTLY the law §8 fixed schema — eight physics floats
 * (cond, heatCap, molar, minMass, maxMass, visc, defaultMass, yieldStress) plus the phase quadruple
 * (minTemp, maxTemp float thresholds; minTarget, maxTarget as resolved {@code matIx}). Absent
 * viscosity → +∞; absent yieldStress → 0; a null/unknown phase target → {@link LutArrays#NO_TARGET};
 * void slot 0 = 0/0/0/finite.
 */
class LutPackTest {

    /** Live water: phase quadruple present (freezes to orge:ice, boils to orge:steam). */
    private static Material water() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "water"))
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN)
                .viscosity(0.001f).minMass(125f).maxMass(1000f)
                .minTemp(273.15f).minTarget(Identifier.fromNamespaceAndPath("orge", "ice"))
                .maxTemp(373.15f).maxTarget(Identifier.fromNamespaceAndPath("orge", "steam"))
                .build();
    }

    private static Material ice() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "ice"))
                .thermalConductivity(2.2f).heatCapacity(2090f).molarMass(0.018f)
                .defaultMass(917f).defaultTemperature(Float.NaN).build();
    }

    private static Material steam() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "steam"))
                .thermalConductivity(0.025f).heatCapacity(2080f).molarMass(0.018f)
                .defaultMass(0.6f).defaultTemperature(Float.NaN)
                .viscosity(1e-4f).minMass(0.6f).maxMass(0.6f).build();
    }

    /** A frozen material: viscosity ABSENT (builder leaves it +∞), no phase targets. */
    private static Material frozen() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "stone"))
                .thermalConductivity(1.0f).heatCapacity(840f).molarMass(0f)
                .defaultMass(2000f).defaultTemperature(Float.NaN)
                .build(); // no viscosity() => +∞; no yield_stress() => 0
    }

    @Test
    void recordCarriesExactlyTheLaw8Schema() {
        RecordComponent[] comps = LutArrays.class.getRecordComponents();
        Set<String> names = Arrays.stream(comps).map(RecordComponent::getName).collect(Collectors.toSet());
        // Exactly the eight physics floats + the phase quadruple + matCount.
        assertEquals(Set.of("cond", "heatCap", "molar", "minMass", "maxMass", "visc", "defaultMass",
                        "yieldStress", "minTemp", "maxTemp", "minTarget", "maxTarget", "matCount"), names);
        // The dropped flag/legacy arrays must not exist.
        for (String banned : List.of("fluid", "gas", "air", "fullMass", "minFlow")) {
            assertFalse(names.contains(banned), "LutArrays must not carry '" + banned + "'");
        }
        long floats = Arrays.stream(comps).filter(c -> c.getType() == float[].class).count();
        long intArrays = Arrays.stream(comps).filter(c -> c.getType() == int[].class).count();
        long ints = Arrays.stream(comps).filter(c -> c.getType() == int.class).count();
        assertEquals(10, floats, "eight physics floats + minTemp + maxTemp");
        assertEquals(2, intArrays, "minTarget + maxTarget (resolved matIx)");
        assertEquals(1, ints, "matCount");
    }

    @Test
    void packEmitsTheEightPhysicsFloatsForANormalMaterial() {
        LutArrays L = LutArrays.pack(List.of(MaterialLut.VACUUM, water()));
        assertEquals(2, L.matCount());
        // water at slot 1 — the exact eight physics values.
        assertEquals(0.6f,   L.cond()[1],        1e-6f);
        assertEquals(4186f,  L.heatCap()[1],     1e-3f);
        assertEquals(0.018f, L.molar()[1],        1e-6f);
        assertEquals(125f,   L.minMass()[1],      1e-4f);
        assertEquals(1000f,  L.maxMass()[1],      1e-4f);
        assertEquals(0.001f, L.visc()[1],         1e-6f);
        assertEquals(1000f,  L.defaultMass()[1],  1e-4f);
        assertEquals(0f,     L.yieldStress()[1],  0f);     // fluid: present, no-op at 0
    }

    @Test
    void packResolvesPhaseTargetsToMatIxAndCarriesThresholds() {
        // Order in the list IS the matIx: VACUUM=0, ice=1, steam=2, water=3 (built explicitly here).
        Material vac = MaterialLut.VACUUM, ice = ice(), steam = steam(), water = water();
        LutArrays L = LutArrays.pack(List.of(vac, ice, steam, water));
        int waterIx = 3;
        assertEquals(273.15f, L.minTemp()[waterIx], 1e-2f);
        assertEquals(373.15f, L.maxTemp()[waterIx], 1e-2f);
        // minTarget => ice (slot 1); maxTarget => steam (slot 2).
        assertEquals(1, L.minTarget()[waterIx], "min_target orge:ice resolves to its matIx");
        assertEquals(2, L.maxTarget()[waterIx], "max_target orge:steam resolves to its matIx");
    }

    @Test
    void absentPhaseTargetsPackAsNoTargetSentinel() {
        LutArrays L = LutArrays.pack(List.of(MaterialLut.VACUUM, frozen()));
        assertEquals(LutArrays.NO_TARGET, L.minTarget()[1], "no min_target => sentinel");
        assertEquals(LutArrays.NO_TARGET, L.maxTarget()[1], "no max_target => sentinel");
        assertEquals(Float.NEGATIVE_INFINITY, L.minTemp()[1], "no min_temp => -∞");
        assertEquals(Float.POSITIVE_INFINITY, L.maxTemp()[1], "no max_temp => +∞");
        assertEquals(0f, L.yieldStress()[1], 0f, "absent yield_stress => 0");
    }

    @Test
    void unknownPhaseTargetIdPacksAsNoTargetSentinel() {
        // water's min_target orge:ice is NOT in this LUT (only VACUUM + water) => sentinel, not a crash.
        LutArrays L = LutArrays.pack(List.of(MaterialLut.VACUUM, water()));
        assertEquals(LutArrays.NO_TARGET, L.minTarget()[1], "unresolved target => sentinel");
        assertEquals(LutArrays.NO_TARGET, L.maxTarget()[1], "unresolved target => sentinel");
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
