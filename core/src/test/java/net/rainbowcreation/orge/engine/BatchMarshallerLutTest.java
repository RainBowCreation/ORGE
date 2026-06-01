package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.MaterialLut;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchMarshallerLutTest {

    private static Material water() {
        // canonical 17-arg ctor: ..., fluid=true, minFlow=125, maxMass=1000, gas=false
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                0.6f, 4186f, 0.001f, 1000f, 0.018f,
                373.15f, 273.15f, null, null,
                Identifier.fromNamespaceAndPath("minecraft", "water"),
                Float.NaN, false, true, 125f, 1000f, false);
    }

    private static Material steam() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "steam"),
                0.025f, 2080f, 0f, 0.6f, 0.018f,
                Float.POSITIVE_INFINITY, 373.15f, null, null,
                Identifier.fromNamespaceAndPath("orge", "steam"),
                Float.NaN, false, true, 0.6f, 0.6f, true);
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
    void lutCarriesThreeMassFieldsAndAirDensityAtIndexZero() {
        List<Material> lut = List.of(MaterialLut.VOID, water(), steam());
        BatchMarshaller.Flat f = BatchMarshaller.flatten(List.of(emptyTask()), lut);

        // index 0 (VOID) gets the AIR density label 1.2 for the kernel's density swap.
        assertEquals(1.2f, f.lutFullMass()[0], 1e-4f);
        // real materials keep their default_mass as fullMass.
        assertEquals(1000f, f.lutFullMass()[1], 1e-4f);
        assertEquals(0.6f, f.lutFullMass()[2], 1e-4f);

        assertEquals(0f, f.lutMinFlow()[0], 0f, "void floor stays 0");
        assertEquals(125f, f.lutMinFlow()[1], 1e-4f);
        assertEquals(0.6f, f.lutMinFlow()[2], 1e-4f);

        assertEquals(1000f, f.lutMaxMass()[1], 1e-4f);
        assertEquals(0.6f, f.lutMaxMass()[2], 1e-4f);

        // TODO(Task 2.1/3.x): the record no longer distinguishes gas from liquid, so the interim LUT
        // packs gas=0 for every material (movability is the only flag now). Gas buoyancy returns via
        // the molar-mass-sorted advection; the gas/air flag arrays are dropped entirely in Task 2.1.
        assertEquals((byte) 0, f.lutGas()[1], "liquid: gas flag 0");
        assertEquals((byte) 0, f.lutGas()[2], "gas distinction deferred — interim gas flag is 0");
    }
}
