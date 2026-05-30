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
                fillChar(FACE, (char) 0), fillChar(FACE, (char) 0), fillChar(FACE, (char) 0),
                fillFloat(FACE, 0f), fillFloat(FACE, 0f), fillFloat(FACE, 0f),
                fillFloat(FACE, 0f), fillFloat(FACE, 0f), fillFloat(FACE, 0f));
    }

    /** A uniform solid (matIx 1) section at temperature T, default mass, void halo. */
    static StepTask solidSection(SubchunkKey key, float T) {
        return new StepTask(key,
                fillChar(SEC_N, (char) 1), fillFloat(SEC_N, 1000f), fillFloat(SEC_N, T), voidHalo());
    }

    /** void (k=0) at index 0, a fluid (matIx 1: fluid=true, viscosity=0.001, defaultMass=1000) at index 1. */
    static List<Material> fluidLut() {
        return List.of(
                material("orge:void", 0f, 0f, 0f),
                new Material(Identifier.parse("orge:fluid"),
                        /*cond*/0f, /*heatCap*/1f, /*viscosity*/0.001f, /*defaultMass*/1000f,
                        /*molarMass*/0.018f, /*boiling*/9999f, /*freezing*/0f,
                        null, null, null, Float.NaN, /*pinned*/false, /*fluid*/true));
    }

    /**
     * A section with exactly ONE fluid pair (matIx 1) — the cell at (0,1,0) full (mass 1000) and
     * the cell directly below (0,0,0) empty (mass 0) — everything else void (matIx 0). Uniform
     * 300 K, all-void halo. After one advection step the lower cell gains mass (fluid falls);
     * because only the pair is fluid there is no horizontal leak, so the pair stays mass-conserved.
     */
    static StepTask fluidSection(SubchunkKey key) {
        char[] mat = fillChar(SEC_N, (char) 0); // void everywhere
        float[] mass = fillFloat(SEC_N, 0f);
        int top = sidx(0, 1, 0);
        int bot = sidx(0, 0, 0);
        mat[top] = (char) 1;                    // fluid full cell
        mat[bot] = (char) 1;                    // fluid empty cell below
        mass[top] = 1000f;
        // mass[bot] stays 0 — the empty cell below.
        return new StepTask(key, mat, mass, fillFloat(SEC_N, 300f), voidHalo());
    }

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }
}
