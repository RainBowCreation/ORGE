package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Arrays;
import java.util.List;

/** Builders shared by the engine tests. */
final class BatchTestSupport {
    static final int SEC_N = BatchMarshaller.SEC_N;
    static final int FACE = NeighborHalo.FACE_CELLS;

    private BatchTestSupport() {}

    static Material material(String id, float cond, float heatCap, float defaultMass) {
        return new Material(Identifier.parse(id), cond, heatCap, /*viscosity*/0f, defaultMass,
                /*molarMass*/0.05f, /*boiling*/9999f, /*freezing*/0f, null, null, null);
    }

    /** void (k=0) at index 0, a stable solid (k=100, heatCap=500) at index 1. */
    static List<Material> stdLut() {
        return List.of(material("orge:void", 0f, 0f, 0f),
                       material("orge:solid", 100f, 500f, 1000f));
    }

    static char[] fillChar(int len, char v)  { char[] a = new char[len];  Arrays.fill(a, v); return a; }
    static float[] fillFloat(int len, float v){ float[] a = new float[len]; Arrays.fill(a, v); return a; }

    /** An all-void halo (no flux on any face). */
    static NeighborHalo voidHalo() {
        return new NeighborHalo(
                fillFloat(FACE, 0f), fillFloat(FACE, 0f), fillFloat(FACE, 0f),
                fillFloat(FACE, 0f), fillFloat(FACE, 0f), fillFloat(FACE, 0f),
                fillChar(FACE, (char) 0), fillChar(FACE, (char) 0), fillChar(FACE, (char) 0),
                fillChar(FACE, (char) 0), fillChar(FACE, (char) 0), fillChar(FACE, (char) 0));
    }

    /** A uniform solid (matIx 1) section at temperature T, default mass, void halo. */
    static StepTask solidSection(SubchunkKey key, float T) {
        return new StepTask(key,
                fillChar(SEC_N, (char) 1), fillFloat(SEC_N, 1000f), fillFloat(SEC_N, T), voidHalo());
    }
}
