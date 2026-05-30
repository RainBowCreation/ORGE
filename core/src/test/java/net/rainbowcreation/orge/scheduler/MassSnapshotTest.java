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
}
