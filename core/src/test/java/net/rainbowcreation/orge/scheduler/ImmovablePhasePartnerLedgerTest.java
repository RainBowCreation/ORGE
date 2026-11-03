package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §9 ledger conserves a substance across ALL its phases, immovable partners included: ice with
 * water + steam, rock with lava. The engine's per-component phase transfer moves the lever-rule share
 * of a component into its partner's slot, so a PARTIAL freeze / melt / solidify lands mass in an
 * immovable species' slot. Slot data below is shaped like the engine's output: slot 0 is the cell's
 * principal component, slots 1..K−1 the rest.
 */
class ImmovablePhasePartnerLedgerTest {

    private static Identifier orge(String p) { return Identifier.fromNamespaceAndPath("orge", p); }

    private static Material.Builder base(String p, float m) {
        return Material.builder(orge(p)).thermalConductivity(1f).heatCapacity(1f).molarMass(0.02f)
                .defaultMass(m).defaultTemperature(Float.NaN);
    }

    private static final char LAVA = 1, STONE = 2, WATER = 3, ICE = 4, STEAM = 5, SOLID = 6;

    private static List<Material> lut() {
        return List.of(MaterialLut.VACUUM,
                base("lava", 2650f).viscosity(500f).minMass(330f).maxMass(2650f)
                        .minTemp(1275f).minTarget(orge("stone")).build(),
                base("stone", 2700f).maxTemp(1450f).maxTarget(orge("lava")).build(),
                base("water", 1000f).viscosity(0.001f).minMass(125f).maxMass(1000f)
                        .minTemp(273f).minTarget(orge("ice"))
                        .maxTemp(373f).maxTarget(orge("steam")).build(),
                base("ice", 917f).maxTemp(273f).maxTarget(orge("water")).build(),
                base("steam", 0.6f).viscosity(1.3e-5f).minMass(0.06f).maxMass(1000f)
                        .minTemp(373f).minTarget(orge("water")).build(),
                base("generic_solid", 2500f).build()); // immovable, no phase link
    }

    /** One cell, K−1 = 2 extra slots; {@code slot0} = (in,out,before,after) of slot 0, then the slots. */
    private static StepValidator.SpeciesMassLedger ledger(char in0, char out0, float before0, float after0,
                                                          char[] inSlot, char[] outSlot,
                                                          float[] slotBefore, float[] slotAfter) {
        StepValidator.SpeciesMassLedger l = new StepValidator.SpeciesMassLedger();
        l.add(new float[]{after0}, new float[]{before0}, new char[]{in0}, new char[]{out0}, lut());
        l.addSlots(slotAfter, slotBefore, inSlot, outSlot, lut());
        return l;
    }

    /** Lake surface freezing under air: 1000 kg water cell, 400 kg of it crosses into an ice slot. */
    @Test
    void partialFreezeIntoIceSlotIsConserved() {
        assertTrue(ledger(WATER, WATER, 1000f, 600f,
                new char[]{0, 0}, new char[]{ICE, 0}, new float[]{0f, 0f}, new float[]{400f, 0f}).conserved());
    }

    @Test
    void partialFreezeWhereIceVanishesFromEverySlotIsCaught() {
        assertFalse(ledger(WATER, WATER, 1000f, 600f,
                new char[]{0, 0}, new char[]{ICE, 0}, new float[]{0f, 0f}, new float[]{0f, 0f}).conserved());
    }

    /** An ice cell partially melting: 500 kg stays ice in slot 0, 417 kg lands in a water slot. */
    @Test
    void partialMeltIntoWaterSlotIsConserved() {
        assertTrue(ledger(ICE, ICE, 917f, 500f,
                new char[]{0, 0}, new char[]{WATER, 0}, new float[]{0f, 0f}, new float[]{417f, 0f}).conserved());
    }

    @Test
    void partialMeltWhereWaterIsCreatedFromNothingIsCaught() {
        assertFalse(ledger(ICE, ICE, 917f, 917f,
                new char[]{0, 0}, new char[]{WATER, 0}, new float[]{0f, 0f}, new float[]{417f, 0f}).conserved());
    }

    /** Lava quenching under water: the lava|steam composite's lava slot partly solidifies into rock. */
    @Test
    void lavaPartiallySolidifyingIntoRockSlotIsConserved() {
        assertTrue(ledger(LAVA, LAVA, 2650f, 1000f,
                new char[]{STEAM, 0}, new char[]{STONE, STEAM}, new float[]{0.6f, 0f}, new float[]{1650f, 0.6f})
                .conserved());
    }

    @Test
    void rockMeltingBackIntoLavaSlotIsConserved() {
        assertTrue(ledger(STONE, STONE, 2700f, 2000f,
                new char[]{0, 0}, new char[]{LAVA, 0}, new float[]{0f, 0f}, new float[]{700f, 0f}).conserved());
    }

    @Test
    void rockVanishingFromEverySlotIsCaught() {
        assertFalse(ledger(LAVA, LAVA, 2650f, 1000f,
                new char[]{0, 0}, new char[]{STONE, 0}, new float[]{0f, 0f}, new float[]{0f, 0f}).conserved());
    }

    /** Ice joins water's family, not lava's: water→lava has no phase link and is still fabrication. */
    @Test
    void waterBecomingLavaIsStillCaught() {
        assertFalse(ledger(WATER, WATER, 1000f, 600f,
                new char[]{0, 0}, new char[]{LAVA, 0}, new float[]{0f, 0f}, new float[]{400f, 0f}).conserved());
        assertFalse(ledger(WATER, WATER, 1000f, 600f,
                new char[]{0, 0}, new char[]{STONE, 0}, new float[]{0f, 0f}, new float[]{400f, 0f}).conserved());
    }

    /** A whole ice block placed: the native ledger declares it in injected[]; undeclared it is fabricated. */
    @Test
    void placedIceIsConservedOnlyWhenDeclared() {
        List<Material> lut = lut();
        StepValidator.SpeciesMassLedger declared = new StepValidator.SpeciesMassLedger();
        declared.add(new float[]{917f}, new float[]{0f}, new char[]{0}, new char[]{ICE}, lut);
        float[] injected = new float[lut.size()];
        injected[ICE] = 917f;
        declared.expect(injected, new float[lut.size()]);
        assertTrue(declared.conserved());

        StepValidator.SpeciesMassLedger undeclared = new StepValidator.SpeciesMassLedger();
        undeclared.add(new float[]{917f}, new float[]{0f}, new char[]{0}, new char[]{ICE}, lut);
        assertFalse(undeclared.conserved());
    }

    /** A broken ice block is stomped to vacuum in the task INPUT before the step: nothing to conserve. */
    @Test
    void brokenIceStompedToVacuumBeforeTheStepIsConserved() {
        StepValidator.SpeciesMassLedger l = new StepValidator.SpeciesMassLedger();
        l.add(new float[]{0f}, new float[]{0f}, new char[]{0}, new char[]{0}, lut());
        assertTrue(l.conserved());
    }

    /** An immovable species with no tracked phase partner stays exempt (placed solid, no event). */
    @Test
    void immovableWithoutPhasePartnerStaysExempt() {
        List<Material> lut = lut();
        StepValidator.SpeciesMassLedger l = new StepValidator.SpeciesMassLedger();
        l.add(new float[]{2500f}, new float[]{0f}, new char[]{0}, new char[]{SOLID}, lut);
        float[] injected = new float[lut.size()];
        injected[SOLID] = 2500f;
        l.expect(injected, new float[lut.size()]);
        assertTrue(l.conserved());
    }

    /** Large share (≫ ε·N) in one step: the false HOLD this fixes. Water+steam+ice all in one cell. */
    @Test
    void freezeBandCrossingWithSteamSlotIsConserved() {
        assertTrue(ledger(WATER, WATER, 1000f, 100f,
                new char[]{STEAM, 0}, new char[]{ICE, STEAM}, new float[]{0.6f, 0f}, new float[]{900f, 0.6f})
                .conserved());
    }
}
