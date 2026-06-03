package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/**
 * §9 gate: SpeciesMassLedger.expect(injected, sealedLoss) teaches conserved() to accept exactly the
 * declared per-species delta (injected − sealedLoss) while still HOLDing genuine fabrication.
 * (Placement-injection spec A4.)
 */
class SpeciesMassLedgerInjectionTest {

    // LUT: [0]=void, [1]=air, [2]=water
    // Built inline (matching StepValidatorMassTest style) — no TestMaterials.movable() factory exists.
    private static final char AIR   = 1;
    private static final char WATER = 2;

    private static Material air() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "air"))
                .thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.002f)
                .defaultMass(1.2f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(1.0f).maxMass(1000f)
                .minTemp(0f)
                .build();
    }

    private static Material water() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "water"))
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(125f).maxMass(1000f)
                .build();
    }

    /** VOID=0, air=1, water=2 */
    private static List<Material> lut() {
        return List.of(MaterialLut.VACUUM, air(), water());
    }

    /**
     * A placement: one cell goes air(1.2) -> water(1000), air relocates into a second (vacuum) cell.
     * Net per species: water +1000 (injected), air 0 (relocated). With the injected delta declared,
     * the gate must accept it.
     */
    @Test
    void acceptsDeclaredInjectionDelta() {
        List<Material> lut = lut();
        // cell 0: air -> water ; cell 1: void -> air (relocation)
        char[] inMat  = { AIR,  0    };
        char[] outMat = { WATER, AIR };
        float[] before = { 1.2f, 0f   };
        float[] after  = { 1000f, 1.2f };

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);

        float[] injected   = new float[lut.size()]; injected[WATER]   = 1000f;
        float[] sealedLoss = new float[lut.size()]; // none
        ledger.expect(injected, sealedLoss);

        assertTrue(ledger.conserved(), "declared placement source is allowed");
    }

    @Test
    void acceptsDeclaredSealedLoss() {
        List<Material> lut = lut();
        // cell 0: air -> water ; air vanished (sealed), water placed
        char[] inMat  = { AIR   };
        char[] outMat = { WATER };
        float[] before = { 1.2f  };
        float[] after  = { 1000f };

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);

        float[] injected   = new float[lut.size()]; injected[WATER]     = 1000f;
        float[] sealedLoss = new float[lut.size()]; sealedLoss[AIR]     = 1.2f;
        ledger.expect(injected, sealedLoss);

        assertTrue(ledger.conserved(), "declared sealed-loss sink is allowed");
    }

    @Test
    void stillHoldsGenuineFabrication() {
        List<Material> lut = lut();
        // cell 0: water stays water but gains 500 kg out of nowhere — NOT declared
        // water maxMass=1000, after=1500 is over cap; add() still accumulates it in sumAfter (over-cap
        // exemption only skips the bound flag, never the conservation sum). tol = 0.01 * 1 cell,
        // |1500-1000| = 500 >> 0.01 -> conserved() returns false. Intent: undeclared delta is held.
        char[] inMat  = { WATER };
        char[] outMat = { WATER };
        float[] before = { 1000f };
        float[] after  = { 1500f }; // 500 conjured, NOT declared

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);
        ledger.expect(new float[lut.size()], new float[lut.size()]); // no injection declared

        assertFalse(ledger.conserved(), "undeclared mass gain still HOLDs");
    }

    private static final char STONE = 3;

    /** Immovable solid (no viscosity => +∞ => movable()==false): a placed wall block. */
    private static Material stone() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "generic_solid"))
                .thermalConductivity(2.0f).heatCapacity(840f).molarMass(0.060f)
                .defaultMass(2500f).defaultTemperature(290f)
                .build(); // no viscosity => frozen => immovable
    }

    /** VOID=0, air=1, water=2, generic_solid=3 (immovable). */
    private static List<Material> lutWithStone() {
        return List.of(MaterialLut.VACUUM, air(), water(), stone());
    }

    /**
     * IN-GAME BUG (durable place→mass): the native {@code apply_injections} records
     * {@code injected[species] += mass} for EVERY placement — including an IMMOVABLE solid (2500 kg).
     * But conserved() only SUMS movable species, so an immovable solid's sumAfter/sumBefore are a
     * structural 0; applying its 2500 kg injected delta against that 0 is a phantom discrepancy. The
     * §9 conservation invariant only governs movable (advecting) mass — immovable mass is created
     * freely by a block place — so conserved() must SKIP untracked species entirely.
     *
     * <p>One placement (2500 kg) was masked in-game only because ε·N for a 5-column region (~4915 kg)
     * happened to exceed 2500; the SECOND solid in a column pushed the phantom error to 5000 > 4915 and
     * the region HELD forever, freezing the column (placed blocks never reached full mass, water inside
     * a wall never flowed). At small N the phantom shows immediately.</p>
     */
    @Test
    void immovableSolidInjectionDoesNotHold() {
        List<Material> lut = lutWithStone();
        // cell 0: air(1.2) -> generic_solid(2500) ; cell 1: void -> air (the displaced air relocates).
        char[] inMat  = { AIR,   0   };
        char[] outMat = { STONE, AIR };
        float[] before = { 1.2f,  0f   };
        float[] after  = { 2500f, 1.2f };

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);

        float[] injected   = new float[lut.size()]; injected[STONE] = 2500f; // native records it
        float[] sealedLoss = new float[lut.size()];
        ledger.expect(injected, sealedLoss);

        assertTrue(ledger.conserved(),
                "an immovable solid placement is not a conservation event; its injected delta must be ignored");
    }

    /**
     * Two immovable solids placed in one cycle: native injected[generic_solid] = 5000. The displaced
     * air is fully relocated (movable, conserves). The two solids must NOT trip the gate — this is the
     * exact in-game freeze (5000 kg phantom > ε·N).
     */
    @Test
    void multipleImmovableSolidInjectionsDoNotHold() {
        List<Material> lut = lutWithStone();
        // cells 0,2: air -> generic_solid ; cells 1,3: void -> air (relocated displaced air).
        char[] inMat  = { AIR,   0,   AIR,   0   };
        char[] outMat = { STONE, AIR, STONE, AIR };
        float[] before = { 1.2f,  0f,  1.2f,  0f   };
        float[] after  = { 2500f, 1.2f, 2500f, 1.2f };

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);

        float[] injected   = new float[lut.size()]; injected[STONE] = 5000f;
        ledger.expect(injected, new float[lut.size()]);

        assertTrue(ledger.conserved(),
                "two immovable solids in one cycle still conserve (movable air balances; solids ignored)");
    }

    /**
     * Guard the fix doesn't blind the gate: a MOVABLE species fabricating mass with no declared delta
     * still HOLDs even when an immovable solid is also present in the same ledger.
     */
    @Test
    void movableFabricationStillHeldAlongsideImmovableInjection() {
        List<Material> lut = lutWithStone();
        // cell 0: air->generic_solid (legit place) ; cell 1: water gains 500 from nowhere (illegal).
        char[] inMat  = { AIR,   WATER };
        char[] outMat = { STONE, WATER };
        float[] before = { 1.2f,  1000f };
        float[] after  = { 2500f, 1500f };

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);

        float[] injected   = new float[lut.size()]; injected[STONE] = 2500f; // only the solid declared
        ledger.expect(injected, new float[lut.size()]);

        assertFalse(ledger.conserved(),
                "undeclared MOVABLE fabrication still HOLDs even with an immovable injection present");
    }

    @Test
    void zeroDeltaIsBackwardCompatible() { // no expect() call => exactly today's behaviour
        List<Material> lut = lut();
        char[] inMat  = { WATER };
        char[] outMat = { WATER };
        float[] before = { 1000f };
        float[] after  = { 1000f };

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);
        // deliberately NO expect() call -> same as today
        assertTrue(ledger.conserved(), "conserved step with no declared delta still passes");
    }
}
