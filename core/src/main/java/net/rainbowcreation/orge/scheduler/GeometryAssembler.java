package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

/**
 * Assembles one section's geometry for a {@link net.rainbowcreation.orge.engine.StepTask}:
 * the per-cell material index ({@code char[4096]}) and per-cell mass ({@code float[4096]},
 * each cell's {@link Material#defaultMass()}). Material identity is supplied per cell by an
 * injected {@link CellMaterials} seam, so this stays pure and unit-testable; the live
 * implementation reads the world's blocks (see {@code MinecraftThermalWorld}).
 */
public final class GeometryAssembler {

    /** Per-cell material lookup: cell index 0..4095 (x-fastest, {@code x+16y+256z}) → material. */
    @FunctionalInterface
    public interface CellMaterials {
        Material at(int cellIndex);
    }

    /**
     * Assembled per-cell geometry arrays (both length {@value SectionData#CELLS}).
     *
     * <p>The arrays are live references handed over without copying — ownership transfers
     * to the caller, which typically wraps them straight into a {@code StepTask}. Treat a
     * {@code Geometry} as consumed after that hand-off; do not retain it expecting an
     * independent snapshot.</p>
     */
    public record Geometry(char[] matIx, float[] mass) {}

    /**
     * The single 16×16 boundary plane of a section that a neighbour contributes to its shared
     * halo face (DESIGN §2). The {@code -X} neighbour contributes its {@code x=15} layer, {@code +X}
     * its {@code x=0} layer, and so on — exactly the cells {@link HaloAssembler} reads via
     * {@code sidx = x + 16y + 256z}. Used by {@link #assembleFace} to compute only those 256 cells.
     */
    public enum Face {
        NEG_X, POS_X, NEG_Y, POS_Y, NEG_Z, POS_Z;

        /** The section cell index ({@code x+16y+256z}) of this plane's {@code k}-th cell (0..255). */
        int cellIndex(int k) {
            int a = k & 15;        // first in-plane axis (0..15)
            int b = (k >> 4) & 15; // second in-plane axis (0..15)
            return switch (this) {
                // X faces: in-plane (a=y, b=z); fixed x = 15 (NEG_X) / 0 (POS_X).
                case NEG_X -> 15 + 16 * a + 256 * b;
                case POS_X -> 0 + 16 * a + 256 * b;
                // Y faces: in-plane (a=x, b=z); fixed y = 15 / 0.
                case NEG_Y -> a + 16 * 15 + 256 * b;
                case POS_Y -> a + 16 * 0 + 256 * b;
                // Z faces: in-plane (a=x, b=y); fixed z = 15 / 0.
                case NEG_Z -> a + 16 * b + 256 * 15;
                case POS_Z -> a + 16 * b + 256 * 0;
            };
        }
    }

    private GeometryAssembler() {}

    /**
     * Builds the geometry arrays for one section. Allocates two {@value SectionData#CELLS}-length
     * arrays per call; callers should cache the result per section version (DESIGN §8) rather than
     * re-assembling every tick.
     */
    public static Geometry assemble(CellMaterials cells, MaterialLut lut) {
        char[] matIx = new char[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            Material m = cells.at(i);
            matIx[i] = lut.indexOf(m);
            mass[i] = m.defaultMass();
        }
        return new Geometry(matIx, mass);
    }

    /**
     * Builds geometry for ONLY the 256 cells of one boundary {@code face} — the single plane a halo
     * neighbour contributes (DESIGN §2 perf follow-on). The returned arrays are still section-sized
     * ({@value SectionData#CELLS}) and indexed by {@code sidx = x+16y+256z}, but only the {@code face}
     * cells are populated (matIx + {@link Material#defaultMass()}); every other cell stays the void
     * default ({@code 0}/{@code 0f}). At those face cells the result is bit-identical to a full
     * {@link #assemble}, while doing ~16× less work. Drop-in for a {@link HaloAssembler.Neighbor}
     * because the halo only ever reads the face cells.
     */
    public static Geometry assembleFace(CellMaterials cells, MaterialLut lut, Face face) {
        char[] matIx = new char[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        for (int k = 0; k < NeighborHaloFaceCells.FACE_CELLS; k++) {
            int i = face.cellIndex(k);
            Material m = cells.at(i);
            matIx[i] = lut.indexOf(m);
            mass[i] = m.defaultMass();
        }
        return new Geometry(matIx, mass);
    }

    /** 16×16 plane = 256 cells per face. Mirrors {@code NeighborHalo.FACE_CELLS} without coupling
     *  this pure assembler to the engine package. */
    private static final class NeighborHaloFaceCells {
        static final int FACE_CELLS = 256;
        private NeighborHaloFaceCells() {}
    }
}
