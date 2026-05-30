package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StepValidatorMassTest {

    private static final char WATER_IX = 1;
    private static final char SOLID_IX = 2;

    private static Material water() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                1f, 1f, 0f, 1000f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, Float.NaN, false, true);
    }

    private static Material genericSolid() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "generic_solid"),
                1f, 1f, 0f, 2500f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null);
    }

    /** VOID=0, water=1, generic_solid=2. */
    private static List<Material> lut() {
        return List.of(MaterialLut.VOID, water(), genericSolid());
    }

    @Test
    void acceptsConservedMassWithinEpsilon() {
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 400f; after[1] = 600f; // moved 100 kg between two cells
        assertTrue(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void rejectsNonConservedMass() {
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 5000f; // 4500 kg created out of nothing
        assertFalse(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void rejectsCellAboveFullMassBound() {
        float[] before = new float[4096]; float[] after = new float[4096];
        // total conserved but one cell exceeds full mass (1000 kg) + epsilon.
        before[0] = 1000f; after[0] = 1000f; after[1] = -0.0f;
        after[0] = 1200f; after[1] = -200f;
        assertFalse(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void solidCellOverBoundIsExemptFromFluidGate() {
        // The reported flood: a lava→obsidian (generic_solid) cell kept lava's 3100 kg while the
        // batch full-mass bound dropped to 2500. As a SOLID it must not fail the §9 fluid gate.
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        char[] matIx = new char[4096]; java.util.Arrays.fill(matIx, WATER_IX);
        matIx[0] = SOLID_IX;
        before[0] = 3100f; after[0] = 3100f; // stale lava mass on the solid, over the 2500 bound

        assertFalse(StepValidator.massConserved(after, before, 2500f),
                "old all-cell gate rejects the over-bound solid (the flood)");
        assertTrue(StepValidator.massConserved(after, before, 2500f, matIx, lut()),
                "fluid-aware gate exempts the solid cell -> no flood");
    }

    @Test
    void solidMassChangeDoesNotCountTowardConservation() {
        // cleanMass clamps the solid 3100->2500 on write-back; that 600 kg delta must not be read as
        // non-conservation, because the solid isn't an advection mass.
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        char[] matIx = new char[4096]; java.util.Arrays.fill(matIx, WATER_IX);
        matIx[0] = SOLID_IX;
        before[0] = 3100f; after[0] = 2500f; // clamped by cleanMass

        assertTrue(StepValidator.massConserved(after, before, 2500f, matIx, lut()));
    }

    @Test
    void fluidCellOverBoundStillFails() {
        // The exemption is solids-only: an over-full FLUID cell must still be rejected.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] matIx = new char[4096]; java.util.Arrays.fill(matIx, WATER_IX);
        before[0] = 1000f; after[0] = 1200f; after[1] = -200f;
        assertFalse(StepValidator.massConserved(after, before, 1000f, matIx, lut()));
    }
}
