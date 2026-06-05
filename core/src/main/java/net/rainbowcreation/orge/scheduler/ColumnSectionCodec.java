package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.section.SectionData;

/**
 * Scatter/gather between a full-height engine column ({@link RegionMarshaller#CHUNK_N} cells, engine
 * index {@code colIdx = x + 16*y + 6144*z}, {@code y in [0,384)}) and the 24 vanilla
 * {@link SectionData} sections of a chunk column.
 *
 * <p>Inverse of {@link ColumnAssembler}'s map: section {@code sectionY in [-4,19]}, section-local
 * cell {@code si = x + 16*sy + 256*z} ({@code sy in [0,15]}) maps to engine column index
 * {@code colIdx = x + 16*(sectionY*16 + sy + 64) + 6144*z}. This is the only place the column⇄section
 * index translation for write-back lives, so the live {@link MinecraftThermalWorld} stays focused.</p>
 */
public final class ColumnSectionCodec {

    /** Engine Y of section-local {@code (sectionY, sy)} (MC→engine: {@code yEngine = yWorld + 64}). */
    public static int engineY(int sectionY, int sy) {
        return sectionY * 16 + sy + 64;
    }

    /** Engine column index for section-local cell {@code (x, sy, z)} of section {@code sectionY}. */
    public static int colIdx(int x, int sectionY, int sy, int z) {
        return x + 16 * engineY(sectionY, sy) + 6144 * z;
    }

    /**
     * Scatter the slice of {@code colT}/{@code colMass} belonging to one section ({@code sectionY})
     * into freshly-allocated section-sized ({@value SectionData#CELLS}) arrays indexed by
     * {@code si = x + 16*sy + 256*z}. {@code out[0]} is temperature, {@code out[1]} is mass.
     */
    public static float[][] sliceSection(float[] colT, float[] colMass, int sectionY) {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        for (int z = 0; z < 16; z++) {
            for (int sy = 0; sy < 16; sy++) {
                int ey = engineY(sectionY, sy);
                int colRow = 16 * ey + 6144 * z;   // + x
                int secRow = 16 * sy + 256 * z;    // + x
                for (int x = 0; x < 16; x++) {
                    t[secRow + x] = colT[colRow + x];
                    m[secRow + x] = colMass[colRow + x];
                }
            }
        }
        return new float[][]{t, m};
    }

    /**
     * Scatter a single float channel ({@code col}) for one section into a freshly-allocated
     * section-sized ({@value SectionData#CELLS}) array indexed by {@code si = x + 16*sy + 256*z}.
     * Uses identical index math to {@link #sliceSection} — this is the single-channel variant
     * used for velocity channels (velX / velY / velZ) whose scatter is the same but whose
     * semantics differ from temperature/mass.
     */
    public static float[] sliceSectionChannel(float[] col, int sectionY) {
        float[] out = new float[SectionData.CELLS];
        for (int z = 0; z < 16; z++) {
            for (int sy = 0; sy < 16; sy++) {
                int ey = engineY(sectionY, sy);
                int colRow = 16 * ey + 6144 * z;   // + x
                int secRow = 16 * sy + 256 * z;    // + x
                for (int x = 0; x < 16; x++) {
                    out[secRow + x] = col[colRow + x];
                }
            }
        }
        return out;
    }

    /**
     * Scatter the species slice of {@code colMat} belonging to one section into a section-sized
     * ({@value SectionData#CELLS}) {@code char[]} indexed by {@code si = x + 16*sy + 256*z}.
     */
    public static char[] sliceSectionMaterials(char[] colMat, int sectionY) {
        char[] mi = new char[SectionData.CELLS];
        for (int z = 0; z < 16; z++) {
            for (int sy = 0; sy < 16; sy++) {
                int ey = engineY(sectionY, sy);
                int colRow = 16 * ey + 6144 * z;
                int secRow = 16 * sy + 256 * z;
                for (int x = 0; x < 16; x++) {
                    mi[secRow + x] = colMat[colRow + x];
                }
            }
        }
        return mi;
    }

    private ColumnSectionCodec() {}
}
