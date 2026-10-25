package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColumnTotalsTest {

    private static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");

    /** Issue #54: a cell the material layer's first writer did not touch has no durable id. Its wire
     *  payload carries {@code orge:unset}; both books must file its mass under {@code orge:vacuum}, as
     *  {@link SectionData#materialAt} reads it — else an untouched save would audit as a MISMATCH. */
    @Test
    void anUnsetCellBooksUnderVacuumOnTheWireAndDecoded() {
        SectionData s = SectionData.ambient(0f);
        s.setMaterialAt(0, STONE);
        s.setMass(0, 2000f);
        s.setMass(5, 1.2f);                       // an untouched (unset) cell that holds mass

        ColumnTotals decoded = new ColumnTotals();
        decoded.add(s);
        ColumnTotals wire = new ColumnTotals();
        wire.add(s.snapshot());

        assertEquals(decoded, wire, "wire and decoded books agree on an unset cell");
        assertEquals(1.2, wire.speciesKg().get(MaterialPalette.VACUUM_ID), 1e-6);
        assertFalse(wire.speciesKg().containsKey(MaterialPalette.UNSET_ID), "orge:unset is not a species");
    }
}
