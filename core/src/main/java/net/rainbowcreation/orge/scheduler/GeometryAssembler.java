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

    /** Assembled per-cell geometry arrays (both length {@value SectionData#CELLS}). */
    public record Geometry(char[] matIx, float[] mass) {}

    private GeometryAssembler() {}

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
}
