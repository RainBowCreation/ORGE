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
        return List.of(MaterialLut.VOID, air(), water());
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
