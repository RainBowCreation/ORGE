package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.MaterialLut;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchMarshallerLutTest {

    private static Material water() {
        // movable (finite visc 0.001), min 125, max/default 1000.
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "water"))
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN)
                .viscosity(0.001f).minMass(125f).maxMass(1000f)
                .minTemp(273.15f).maxTemp(373.15f)
                .representativeBlock(Identifier.fromNamespaceAndPath("minecraft", "water"))
                .build();
    }

    private static Material steam() {
        // a light movable gas: finite visc, min/max/default 0.6.
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "steam"))
                .thermalConductivity(0.025f).heatCapacity(2080f).molarMass(0.018f)
                .defaultMass(0.6f).defaultTemperature(Float.NaN)
                .viscosity(0.0001f).minMass(0.6f).maxMass(0.6f)
                .maxTemp(373.15f)
                .representativeBlock(Identifier.fromNamespaceAndPath("orge", "steam"))
                .build();
    }

    /** A minimal StepTask batch so flatten() runs; geometry content is irrelevant to the LUT arrays. */
    private static StepTask emptyTask() {
        char[] mat = new char[BatchMarshaller.SEC_N];      // all VOID (index 0)
        float[] mass = new float[BatchMarshaller.SEC_N];
        float[] temp = new float[BatchMarshaller.SEC_N];
        int f = NeighborHalo.FACE_CELLS;
        // NeighborHalo is an 18-array flat record: 6 temp faces, 6 mat faces, 6 mass faces.
        NeighborHalo halo = new NeighborHalo(
                new float[f], new float[f], new float[f], new float[f], new float[f], new float[f],
                new char[f],  new char[f],  new char[f],  new char[f],  new char[f],  new char[f],
                new float[f], new float[f], new float[f], new float[f], new float[f], new float[f]);
        return new StepTask(null, mat, mass, temp, halo);
    }

    @Test
    void lutCarriesSixPhysicsArraysWithDisplaceableVoidAtIndexZero() {
        List<Material> lut = List.of(MaterialLut.VOID, water(), steam());
        BatchMarshaller.Flat f = BatchMarshaller.flatten(List.of(emptyTask()), lut);

        // Re-pointed from the dropped fullMass/minFlow/gas flag arrays to the six-physics model.
        // Slot 0 (VOID) is now 0/0/0 masses with a FINITE (displaceable, not frozen) viscosity —
        // replaces the old AIR_DENSITY=1.2 fullMass sentinel.
        assertEquals(0f, f.lutMinMass()[0], 0f, "void floor 0");
        assertEquals(0f, f.lutMaxMass()[0], 0f, "void cap 0");
        assertEquals(0f, f.lutMolar()[0], 0f, "void molar 0");
        assertTrue(Float.isFinite(f.lutVisc()[0]), "void displaceable ⇒ finite visc");

        // min/max mass carried per material (replaces fullMass+minFlow).
        assertEquals(125f, f.lutMinMass()[1], 1e-4f);
        assertEquals(1000f, f.lutMaxMass()[1], 1e-4f);
        assertEquals(0.6f, f.lutMinMass()[2], 1e-4f);
        assertEquals(0.6f, f.lutMaxMass()[2], 1e-4f);

        // movability is now visc-finite (both water and steam are movable); the gas flag is gone —
        // gas vs liquid behaviour comes from molar-mass-sorted advection in Phase 3, not a flag.
        assertTrue(Float.isFinite(f.lutVisc()[1]), "water movable");
        assertTrue(Float.isFinite(f.lutVisc()[2]), "steam movable");
        assertEquals(0.018f, f.lutMolar()[1], 1e-6f);
    }
}
