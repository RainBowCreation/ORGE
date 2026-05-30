package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link MassSnapshot#select}, the per-cell mass-selection rule the
 * snapshot uses to decide a cell's input mass (§10 advection): persisted advected mass is
 * preserved, freshly-placed fluid cells (stored ≈0) are seeded once to full, solids/air keep
 * their stored value. This is the fix for advection-result discard + water-level oscillation.
 */
class MassSnapshotTest {

    private static final char WATER_IX = 1;
    private static final char STONE_IX = 2;

    /** A fluid material with the given defaultMass. */
    private static Material water(float defaultMass) {
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                1f, 1f, 0f, defaultMass, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, Float.NaN, false, true);
    }

    /** A non-fluid (solid) material. */
    private static Material stone(float defaultMass) {
        return new Material(Identifier.fromNamespaceAndPath("orge", "stone"),
                1f, 1f, 0f, defaultMass, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null);
    }

    /** Materials list with VOID at 0, water at 1, stone at 2. */
    private static List<Material> lut() {
        return List.of(MaterialLut.VOID, water(640f), stone(2000f));
    }

    @Test
    void noSectionReturnsBlockDerivedGeoMassVerbatim() {
        // First-ever simulation of this section: no stored mass exists; use the geometry seed.
        float geoMass = 640f;
        float result = MassSnapshot.select(0f /*stored, unused*/, WATER_IX, lut(), false, geoMass);
        assertEquals(640f, result, 0f, "no section -> block-derived geo mass verbatim");

        float geoMassSolid = 2000f;
        assertEquals(2000f,
                MassSnapshot.select(0f, STONE_IX, lut(), false, geoMassSolid), 0f);
    }

    @Test
    void fluidCellWithEmptyStoreIsSeededToDefaultMass() {
        // Section exists but this fluid cell has ~0 stored mass: freshly-placed water -> seed full.
        float result = MassSnapshot.select(0f, WATER_IX, lut(), true, 640f);
        assertEquals(640f, result, 0f, "placed-water entry point: seed to fluid defaultMass");
    }

    @Test
    void fluidCellWithStoredMassIsPreserved() {
        // Section exists and the cell already carries advected mass: keep it (oscillation fix).
        float result = MassSnapshot.select(640f, WATER_IX, lut(), true, 640f);
        assertEquals(640f, result, 0f, "advected mass preserved");

        // A partially-drained cell keeps its real level instead of snapping back to full.
        assertEquals(123f,
                MassSnapshot.select(123f, WATER_IX, lut(), true, 640f), 0f,
                "partial advected mass preserved, not reset to full");
    }

    @Test
    void nonFluidCellWithEmptyStoreStaysEmpty() {
        // A solid/air cell with 0 stored mass must NOT be seeded.
        float result = MassSnapshot.select(0f, STONE_IX, lut(), true, 2000f);
        assertEquals(0f, result, 0f, "do not seed solids/air");
    }

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    private static boolean inPlane(GeometryAssembler.Face face, int x, int y, int z) {
        return switch (face) {
            case NEG_X -> x == 15; case POS_X -> x == 0;
            case NEG_Y -> y == 15; case POS_Y -> y == 0;
            case NEG_Z -> z == 15; case POS_Z -> z == 0;
        };
    }

    @Test
    void selectFaceMatchesSelectAllAtFaceCellsAndSeedsFluidEntryPoints() {
        // Stored mass: some fluid cells drained to ~0 (entry points), some carrying advected mass.
        int cells = net.rainbowcreation.orge.section.SectionData.CELLS;
        float[] stored = new float[cells];
        char[] matIx = new char[cells];
        float[] geoMass = new float[cells];
        for (int i = 0; i < cells; i++) {
            // Alternate water / stone; every 3rd water cell is a drained entry point (stored 0).
            boolean water = (i % 2 == 0);
            matIx[i] = water ? WATER_IX : STONE_IX;
            geoMass[i] = water ? 640f : 2000f;
            stored[i] = (water && i % 3 == 0) ? 0f : (water ? 300f : 2000f);
        }

        float[] all = MassSnapshot.selectAll(stored, matIx, lut(), true, geoMass);

        for (GeometryAssembler.Face face : GeometryAssembler.Face.values()) {
            // Face-only geometry mirrors assembleFace: matIx/geoMass populated only at face cells.
            char[] faceMat = new char[cells];
            float[] faceGeo = new float[cells];
            for (int z = 0; z < 16; z++)
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++)
                        if (inPlane(face, x, y, z)) {
                            int i = sidx(x, y, z);
                            faceMat[i] = matIx[i];
                            faceGeo[i] = geoMass[i];
                        }

            float[] faceMass = MassSnapshot.selectFace(stored, faceMat, lut(), true, faceGeo, face);
            for (int z = 0; z < 16; z++)
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++)
                        if (inPlane(face, x, y, z)) {
                            int i = sidx(x, y, z);
                            assertEquals(all[i], faceMass[i], 0f,
                                    "selectFace must equal selectAll at face cell " + i + " for " + face);
                        }
        }
    }
}
