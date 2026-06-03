package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StepValidatorMassTest {

    private static final char WATER_IX = 1;
    private static final char SOLID_IX = 2;
    private static final char LAVA_IX = 3;
    private static final char STEAM_IX = 4;
    private static final char AIR_IX = 5;

    private static Material water() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "water"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(1000f).defaultTemperature(Float.NaN)
                .viscosity(0f) // movable
                .build();
    }

    private static Material genericSolid() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "generic_solid"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(2500f).defaultTemperature(Float.NaN)
                .build(); // frozen
    }

    /** VOID=0, water=1, generic_solid=2. */
    private static List<Material> lut() {
        return List.of(MaterialLut.VACUUM, water(), genericSolid());
    }

    private static Material lava() {
        // movable, floor 400, cap 3100.
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "lava"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(3100f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(400f).maxMass(3100f)
                .build();
    }

    private static Material steam() {
        // a light movable gas, min/max/default 0.6.
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "steam"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(0.6f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(0.6f).maxMass(0.6f)
                .build();
    }

    private static Material air() {
        // Real orge:air: a movable finite gas (~1.2 kg per cell). Under the canonical schema there is
        // no separate AIR state — finite viscosity makes it movable (a tracked advection species).
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "air"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(1.2f).defaultTemperature(Float.NaN)
                .viscosity(0f)
                .build();
    }

    /** VOID=0, water=1, generic_solid=2, lava=3, steam=4, air=5. */
    private static List<Material> perSpeciesLut() {
        return List.of(MaterialLut.VACUUM, water(), genericSolid(), lava(), steam(), air());
    }

    @Test
    void perSpeciesAcceptsEachSpeciesConserved() {
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        // water moves 100 kg cell0->cell1; both water in AND out -> conserved per species.
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 400f; after[1] = 600f;
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void perSpeciesRejectsCrossSpeciesLeak() {
        // Total mass conserved, but water lost 100 kg and lava gained 100 kg (a mislabeled drain).
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        outMat[0] = LAVA_IX;                // cell0 is lava after the (bogus) step
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 600f; after[1] = 400f;   // lava sumAfter=600 vs sumBefore=0, water short by 100 -> fails
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void perSpeciesBoundsEachCellByItsOwnNegativeFloor() {
        // The per-cell bound on the OUTPUT species catches a NEGATIVE water cell (-200 kg): cell1 is
        // water-out and -200 < -ε, so the bound rejects regardless of the batch max (the old scalar
        // bound would also reject negatives, but the per-species bound is what guards each species'
        // OWN [−ε, max] window). The over-cap exemption is over-cap-ONLY and never relaxes the lower
        // (negative) bound, so a negative mass always fails.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        before[0] = 1000f; after[0] = 800f; after[1] = -200f; // cell1 negative -> bound fails
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void wettingAnEmptyVacuumCellIsConserved() {
        // A water cell (1000 kg) donates 125 kg into an adjacent VACUUM cell (matIx 0, mass 0), which
        // becomes water. Dual-index sum: water sumBefore = donor 1000 (recipient was vacuum -> no species,
        // not counted in sumBefore); water sumAfter = donor 875 + recipient 125 = 1000. Conserved. (Note:
        // this is wetting into VACUUM; wetting into REAL air must DISPLACE the air — see
        // liquidDisplacesAirIntoNeighbourIsConserved.)
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        // cell0 was air, becomes water (the wetted recipient); cell1 is the donor water.
        inMat[0] = 0;            // air in
        outMat[0] = WATER_IX;    // water out (wetted)
        before[0] = 0f;          // air had no mass
        after[0] = 125f;         // received 125 kg of water
        before[1] = 1000f;       // donor full water
        after[1] = 875f;         // donor gave 125 kg
        // rest is a flat conserved water field (in==out==water, before==after)
        java.util.Arrays.fill(before, 2, 4096, 500f);
        java.util.Arrays.fill(after, 2, 4096, 500f);
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void liquidDisplacesAirIntoNeighbourIsConserved() {
        // §11 CONTRACT: air is a tracked, CONSERVED species. A liquid spreading into real-air cells must
        // DISPLACE that air (relocate it), not consume it. Here a donor water cell sheds dm into 100 air
        // cells (air-in / water-out); the 100*1.2 kg of displaced air RE-APPEARS in 100 receiver air
        // cells whose mass rises by 1.2 kg each (air-in / air-out). Air's own dual index now balances:
        //   air sumBefore = wetted 100*1.2 + receivers 100*1.2 (+ background, balanced)
        //   air sumAfter  = wetted 100*0   + receivers 100*2.4 (+ background)  -> equal.
        // Under the OLD air-credit (which dropped air's own conservation and instead padded the fluid's
        // sumBefore) this displacement was mis-handled. Now it PASSES iff air is truly conserved.
        final int wet = 100;
        final float dm = 5f;
        final float airMass = 1.2f;
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, AIR_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, AIR_IX);
        // background: a flat air field (air in == air out, before==after==1.2) -> balances in air's sum.
        java.util.Arrays.fill(before, airMass);
        java.util.Arrays.fill(after, airMass);
        // donor water cell (index 0): full water, water-in/water-out, sheds wet*dm into the wetted cells.
        inMat[0] = WATER_IX; outMat[0] = WATER_IX;
        before[0] = 1000f; after[0] = 1000f - wet * dm;
        // wetted cells [1..wet]: air-in, WATER-out. They received water; the air they held is displaced
        // (relocated), so the air mass leaves these cells.
        for (int i = 1; i <= wet; i++) {
            inMat[i] = AIR_IX;          // air in (before == 1.2)
            outMat[i] = WATER_IX;       // water out
            after[i] = airMass + dm;    // water mass deposited (the displaced 1.2 air left, dm water in)
        }
        // receiver air cells [wet+1 .. 2*wet]: air-in, AIR-out. Each absorbs the 1.2 kg displaced from a
        // wetted cell -> mass rises 1.2 -> 2.4. Air's species sum is conserved (donor air relocated here).
        for (int i = wet + 1; i <= 2 * wet; i++) {
            inMat[i] = AIR_IX; outMat[i] = AIR_IX;
            after[i] = airMass + airMass; // received the displaced 1.2
        }
        // water sumBefore = donor 1000; sumAfter = donor 500 + 100*(1.2+5)=720 -> 1220 != 1000?  No:
        //   the wetted cells' water-out is 6.2 each; that is 1.2 displaced-air-equivalent volume PLUS 5
        //   water. Water must conserve on its OWN index: the 1.2 in each wetted cell is AIR mass that was
        //   pushed out, NOT water. So model the wetted water-out as exactly the deposited dm (5), with the
        //   pre-existing 1.2 accounted as air. Adjust: wetted water-out = dm.
        for (int i = 1; i <= wet; i++) after[i] = dm; // water deposited only; the 1.2 air went to a receiver
        // water sumBefore = 1000; water sumAfter = (1000-500) + 100*5 = 500 + 500 = 1000 -> conserved.
        // air sumBefore = wetted 100*1.2 + receivers 100*1.2 (+bg) ; air sumAfter = wetted 0 + receivers
        //   100*2.4 (+bg) -> 240 == 240 -> conserved.
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "liquid displacing air into neighbours conserves BOTH water and air");
    }

    @Test
    void airConsumedFromNothingIsRejected() {
        // §11 CONTRACT: air can NOT simply vanish (the relabel bug). A wetted air cell whose 1.2 kg is
        // NOT relocated anywhere -> air sumBefore exceeds air sumAfter by 100*1.2 = 120 kg, far over the
        // ε·N ≈ 40.96 kg tolerance -> REJECT. (This is exactly the old air-credit's blind spot: it would
        // have ACCEPTED this mass-from-nothing because air was untracked.)
        final int wet = 100; final float dm = 5f; final float airMass = 1.2f;
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, AIR_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, AIR_IX);
        java.util.Arrays.fill(before, airMass);
        java.util.Arrays.fill(after, airMass);
        inMat[0] = WATER_IX; outMat[0] = WATER_IX;
        before[0] = 1000f; after[0] = 1000f - wet * dm;
        for (int i = 1; i <= wet; i++) {
            inMat[i] = AIR_IX; outMat[i] = WATER_IX;
            after[i] = dm; // air's 1.2 simply disappears, nowhere relocated -> air not conserved
        }
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "air consumed (relabelled to water) with no relocation must be rejected");
    }

    @Test
    void densitySwapWithVacuumIsConserved() {
        // Steam below VACUUM rises (Plan-1 swap into the void sentinel): lower cell steam(0.6) -> vacuum
        // (matIx 0), upper cell vacuum(0) -> steam(0.6). Vacuum is no species (contributes 0). Steam's
        // dual index: sumBefore = lower-in 0.6; sumAfter = upper-out 0.6. ✓
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; char[] outMat = new char[4096]; // all VOID/vacuum (0) by default
        int lo = 0, up = 1;
        inMat[lo] = STEAM_IX; outMat[lo] = 0;        before[lo] = 0.6f; after[lo] = 0f;
        inMat[up] = 0;        outMat[up] = STEAM_IX;  before[up] = 0f;   after[up] = 0.6f;
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void airDensitySwapWithSteamIsConserved() {
        // Air is now a tracked species, so an air<->steam buoyancy swap must conserve BOTH on their own
        // index. Steam(0.6) below air(1.2): they swap. Lower: steam-in 0.6 / air-out 1.2. Upper:
        // air-in 1.2 / steam-out 0.6.
        //   steam sumBefore = lower-in 0.6 ; steam sumAfter = upper-out 0.6 -> conserved.
        //   air   sumBefore = upper-in 1.2 ; air   sumAfter = lower-out 1.2 -> conserved.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; char[] outMat = new char[4096]; // VOID(0) elsewhere
        int lo = 0, up = 1;
        inMat[lo] = STEAM_IX; outMat[lo] = AIR_IX;   before[lo] = 0.6f; after[lo] = 1.2f;
        inMat[up] = AIR_IX;   outMat[up] = STEAM_IX; before[up] = 1.2f; after[up] = 0.6f;
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "air<->steam swap conserves each species on its own index");
    }

    @Test
    void vacuumCellsContributeZeroToEverySum() {
        // VACUUM (matIx 0, mass ~0) is no species: it must contribute nothing to any sum and never break
        // the ledger. A field of pure vacuum with a single conserved water move passes.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; char[] outMat = new char[4096]; // all vacuum (0)
        // one water pair: move 100 kg cell0->cell1
        inMat[0] = WATER_IX; outMat[0] = WATER_IX; before[0] = 500f; after[0] = 400f;
        inMat[1] = WATER_IX; outMat[1] = WATER_IX; before[1] = 500f; after[1] = 600f;
        // the other 4094 cells are vacuum with arbitrary (even non-zero) before mass that must be ignored
        // because their species index is 0; set a stray before to prove it is not summed.
        before[2] = 9999f; // vacuum cell carrying junk mass -> ignored (index 0)
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "vacuum cells (index 0) contribute 0 to every species sum");
    }

    @Test
    void airCellMayHoldUpToItsMaxMass() {
        // §11 per-cell bound: a compressed air cell may hold up to air's max_mass (1000) and still be
        // within bound. Air-in/air-out, conserved, one cell at 1000.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, AIR_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, AIR_IX);
        java.util.Arrays.fill(before, 1.2f); java.util.Arrays.fill(after, 1.2f);
        // squeeze: 800 cells worth of air piled into cell0 (conserved against the donors below).
        before[0] = 1.2f; after[0] = 1000f; // air compressed to its cap
        // donors give up 998.8 spread over many cells (keep each donor air-in/air-out, mass falls a touch)
        float give = (1000f - 1.2f);
        int donors = 999; float per = give / donors;
        for (int i = 1; i <= donors; i++) after[i] = 1.2f - per;
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "an air cell at its 1000 kg max_mass is within bound and conserved");
    }

    @Test
    void airFabricatedFromNothingFailsConservation() {
        // The bound EXEMPTS an over-cap cell (the transient compressed/boil parcel) for air just as for a
        // fluid, so the bound alone never catches invented air. The CONSERVATION sum (never exempted) is
        // what forbids air-from-nothing: an air cell that gains 1000 kg with no matching air donor anywhere
        // -> air sumAfter exceeds sumBefore by 1000, far over ε·N -> REJECT.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, AIR_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, AIR_IX);
        java.util.Arrays.fill(before, 1.2f); java.util.Arrays.fill(after, 1.2f);
        after[0] = 1001.2f; // +1000 kg of air from nowhere (also over cap -> bound-exempt, sum still catches)
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "air invented from nothing is caught by the conservation sum, not the (exempt) bound");
    }

    @Test
    void boilVolumeOverCapCellIsExemptFromTheBoundButCountedInTheSum() {
        // A §7/engine boil left one steam cell holding 1000 kg (way over steam's 0.6 cap). It is exempt
        // from the per-cell BOUND (over its own output cap) so it does not fail on the cap, AND it is
        // still COUNTED in steam's conservation sum (never exempted from the sum). Here a neighbouring
        // water cell lost exactly 1000 kg (the boil source on the input side, mislabeled as steam? no:
        // model the realistic relaxation step where the over-cap steam stays steam in AND out and just
        // sheds mass to a steam neighbour). steam sumBefore = 1000 + 0.6, sumAfter = 999.4 + 0.6+... we
        // model the simplest conserved case: the over-cap cell sheds 0.4 to an adjacent steam cell.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, STEAM_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, STEAM_IX);
        before[0] = 1000f; after[0] = 999.6f;  // over-cap steam, sheds 0.4 (still way over the 0.6 cap)
        before[1] = 0.6f;  after[1] = 1.0f;     // neighbour steam gains 0.4 (also over the 0.6 cap)
        java.util.Arrays.fill(before, 2, 4096, 0.6f);
        java.util.Arrays.fill(after, 2, 4096, 0.6f);
        // Both cell0 and cell1 are over the 0.6 cap -> exempt from the BOUND; the conservation sum
        // (steam in == steam out, total unchanged) still passes -> gate returns true.
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void overCapCellThatInventsMassStillFails() {
        // The bound exemption can NEVER hide invented mass: an over-cap steam cell that gains 1000 kg
        // from nowhere (no matching loss anywhere) is exempt from the BOUND but the conservation sum
        // (steam sumAfter exceeds sumBefore by 1000 kg, far over the ε·N ≈ 40.96 kg tolerance) rejects it.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, STEAM_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, STEAM_IX);
        before[0] = 0.6f; after[0] = 1000.6f;   // over the 0.6 cap (bound-exempt) but +1000 invented
        java.util.Arrays.fill(before, 1, 4096, 0.6f);
        java.util.Arrays.fill(after, 1, 4096, 0.6f);
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void waterToLavaMixWithNoAirStillRejected() {
        // PROTECTION: the new air-credit branch fires ONLY for air-in/fluid-out. A genuine fluid->fluid
        // relabel that invents mass (no air anywhere) must still be rejected. Water cells become lava-out
        // and gain 100 kg from nowhere: lava sumAfter exceeds (its 0) sumBefore, water sumBefore exceeds
        // its sumAfter -> both species off, far over ε·N -> false. (If the air-credit ever mis-fired on a
        // non-air input, lava's before would be wrongly padded and could mask the invention.)
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        outMat[0] = LAVA_IX;              // water-in, lava-out (NOT air-in) -> air-credit must NOT apply
        after[0] = 600f;                  // lava cell invents 100 kg (no matching water loss)
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void swapDisplacementConservesPerSpecies() {
        // DISPLACEMENT SWAP (native fall pass): water falls into a real-air cell below. The donor cell A
        // goes water-in/air-out (its water sank; the air's ~1.2 kg rose into it). The cell below B goes
        // air-in/water-out (it received the water). With air as a TRACKED species this conserves on BOTH
        // indices directly — no cross-species credit needed:
        //   Donor A : water-in 1000 -> air-out 1.2  (water displaced down, air risen up)
        //   Below B : air-in 1.2     -> water-out 1000 (water arrived)
        //   water sumBefore = A-in 1000 ; water sumAfter = B-out 1000   -> conserved.
        //   air   sumBefore = B-in 1.2  ; air   sumAfter = A-out 1.2    -> conserved.
        final int swaps = 100; final float airMass = 1.2f;
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; char[] outMat = new char[4096]; // VOID(0) elsewhere -> inert
        for (int s = 0; s < swaps; s++) {
            int a = 2 * s, b = 2 * s + 1;
            inMat[a] = WATER_IX; outMat[a] = AIR_IX;   before[a] = 1000f;    after[a] = airMass; // donor water->air
            inMat[b] = AIR_IX;   outMat[b] = WATER_IX; before[b] = airMass;  after[b] = 1000f;   // below air->water
        }
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "a pool of displacement swaps must conserve per species (pre-fix over-counts air by 1.2/swap)");
    }

    @Test
    void pureWaterMoveAmongAirConserves() {
        // REGRESSION (no fluid<->air interaction): a flat background air field (air-in/air-out, balanced)
        // plus a pure water move (water-in/water-out, 100 kg cell0->cell1). Air conserves trivially on its
        // own index (before==after everywhere) and water conserves on its own -> gate true. Guards that the
        // background air, now a tracked species, does not perturb a pure-fluid result.
        final float airMass = 1.2f;
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, AIR_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, AIR_IX);
        java.util.Arrays.fill(before, airMass);
        java.util.Arrays.fill(after, airMass);
        inMat[0] = WATER_IX; outMat[0] = WATER_IX; before[0] = 500f; after[0] = 400f;
        inMat[1] = WATER_IX; outMat[1] = WATER_IX; before[1] = 500f; after[1] = 600f;
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "a pure water move within a balanced air field conserves both species");
    }

    @Test
    void genuineFabricationStillRejected() {
        // PROTECTION: a water cell's OUTPUT mass is inflated with NO matching air-out donor anywhere. The
        // new swap branch only credits a fluid-in/AIR-out cell; an ordinary inflated water-out cell still
        // hits the normal sumAfter[out] path, so water sumAfter exceeds sumBefore by the invented amount.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 1000f;                 // +500 kg of water from nowhere, no air-out donor to credit
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "water fabricated with no air-out swap donor must still be rejected");
    }

    @Test
    void acceptsConservedMassWithinEpsilon() {
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 400f; after[1] = 600f; // moved 100 kg between two cells
        assertTrue(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void rejectsNonConservedMass() {
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 5000f; // 4500 kg created out of nothing
        assertFalse(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void rejectsCellAboveFullMassBound() {
        float[] before = new float[4096]; float[] after = new float[4096];
        // total conserved but one cell exceeds full mass (1000 kg) + epsilon.
        before[0] = 1000f; after[0] = 1000f; after[1] = -0.0f;
        after[0] = 1200f; after[1] = -200f;
        assertFalse(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void solidCellOverBoundIsExemptFromFluidGate() {
        // The reported flood: a lava→obsidian (generic_solid) cell kept lava's 3100 kg while the
        // batch full-mass bound dropped to 2500. As a SOLID it must not fail the §9 fluid gate.
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        char[] matIx = new char[4096]; java.util.Arrays.fill(matIx, WATER_IX);
        matIx[0] = SOLID_IX;
        before[0] = 3100f; after[0] = 3100f; // stale lava mass on the solid, over the 2500 bound

        assertFalse(StepValidator.massConserved(after, before, 2500f),
                "old all-cell gate rejects the over-bound solid (the flood)");
        assertTrue(StepValidator.massConserved(after, before, 2500f, matIx, lut()),
                "fluid-aware gate exempts the solid cell -> no flood");
    }

    @Test
    void solidMassChangeDoesNotCountTowardConservation() {
        // cleanMass clamps the solid 3100->2500 on write-back; that 600 kg delta must not be read as
        // non-conservation, because the solid isn't an advection mass.
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        char[] matIx = new char[4096]; java.util.Arrays.fill(matIx, WATER_IX);
        matIx[0] = SOLID_IX;
        before[0] = 3100f; after[0] = 2500f; // clamped by cleanMass

        assertTrue(StepValidator.massConserved(after, before, 2500f, matIx, lut()));
    }

    @Test
    void fluidCellOverBoundStillFails() {
        // The exemption is solids-only: an over-full FLUID cell must still be rejected.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] matIx = new char[4096]; java.util.Arrays.fill(matIx, WATER_IX);
        before[0] = 1000f; after[0] = 1200f; after[1] = -200f;
        assertFalse(StepValidator.massConserved(after, before, 1000f, matIx, lut()));
    }

    // ---- Batch-level conservation ledger (cross-section seam transfers) ----

    @Test
    void ledgerAcceptsCrossSeamTransferThatPerSectionWouldReject() {
        // Section A loses 800 kg water; Section B gains 800 kg water (a cross-seam fall). PER-SECTION
        // each is non-conserving (A short by 800, B long by 800 — both far over ε·N ≈ 40.96), but the
        // batch ledger sums BOTH sections and the transfer cancels -> conserved.
        float[] beforeA = new float[4096]; float[] afterA = new float[4096];
        float[] beforeB = new float[4096]; float[] afterB = new float[4096];
        char[] inA = new char[4096]; java.util.Arrays.fill(inA, WATER_IX);
        char[] outA = new char[4096]; java.util.Arrays.fill(outA, WATER_IX);
        char[] inB = new char[4096]; java.util.Arrays.fill(inB, WATER_IX);
        char[] outB = new char[4096]; java.util.Arrays.fill(outB, WATER_IX);
        // A: a full 1000 kg column cell drains to 200 kg (lost 800 across the seam).
        java.util.Arrays.fill(beforeA, 500f); java.util.Arrays.fill(afterA, 500f);
        beforeA[0] = 1000f; afterA[0] = 200f;
        // B: a 200 kg cell fills to 1000 kg (gained the 800).
        java.util.Arrays.fill(beforeB, 500f); java.util.Arrays.fill(afterB, 500f);
        beforeB[0] = 200f; afterB[0] = 1000f;

        // Sanity: each section ALONE fails the per-section gate.
        assertFalse(StepValidator.massConservedPerSpecies(afterA, beforeA, inA, outA, perSpeciesLut()),
                "section A alone is short 800 kg");
        assertFalse(StepValidator.massConservedPerSpecies(afterB, beforeB, inB, outB, perSpeciesLut()),
                "section B alone is long 800 kg");

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(afterA, beforeA, inA, outA, perSpeciesLut());
        ledger.add(afterB, beforeB, inB, outB, perSpeciesLut());
        assertTrue(ledger.conserved(),
                "batch ledger cancels the cross-seam transfer -> conserved");
    }

    @Test
    void ledgerRejectsGenuineFabricationAcrossBatch() {
        // Section B gains 800 kg water with NO matching donor anywhere in the batch -> fabrication.
        float[] beforeA = new float[4096]; float[] afterA = new float[4096];
        float[] beforeB = new float[4096]; float[] afterB = new float[4096];
        char[] inA = new char[4096]; java.util.Arrays.fill(inA, WATER_IX);
        char[] outA = new char[4096]; java.util.Arrays.fill(outA, WATER_IX);
        char[] inB = new char[4096]; java.util.Arrays.fill(inB, WATER_IX);
        char[] outB = new char[4096]; java.util.Arrays.fill(outB, WATER_IX);
        // A: perfectly conserved on its own.
        java.util.Arrays.fill(beforeA, 500f); java.util.Arrays.fill(afterA, 500f);
        // B: a 200 kg cell jumps to 1000 kg, no donor.
        java.util.Arrays.fill(beforeB, 500f); java.util.Arrays.fill(afterB, 500f);
        beforeB[0] = 200f; afterB[0] = 1000f;

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(afterA, beforeA, inA, outA, perSpeciesLut());
        ledger.add(afterB, beforeB, inB, outB, perSpeciesLut());
        assertFalse(ledger.conserved(), "batch gains 800 kg from nowhere -> not conserved");
    }

    @Test
    void singleEntryLedgerMatchesPerSectionVerdict() {
        // Parity: a one-entry ledger gives the SAME verdict as massConservedPerSpecies across several
        // cases (wetting, swap, drain/move, over-cap exemption, fabrication). The two must never diverge.
        record Case(String name, float[] after, float[] before, char[] in, char[] out, boolean expect) {}
        java.util.List<Case> cases = new java.util.ArrayList<>();

        // (1) plain move (conserved)
        {
            float[] b = new float[4096]; float[] a = new float[4096];
            char[] in = new char[4096]; java.util.Arrays.fill(in, WATER_IX);
            char[] out = new char[4096]; java.util.Arrays.fill(out, WATER_IX);
            java.util.Arrays.fill(b, 500f); java.util.Arrays.fill(a, 500f);
            a[0] = 400f; a[1] = 600f;
            cases.add(new Case("move", a, b, in, out, true));
        }
        // (2) liquid DISPLACES air into receivers (conserved): wetted air-in/water-out cells shed their
        //     1.2 kg air into receiver air cells (air-in/air-out), so air conserves on its own index.
        {
            final int wet = 100; final float dm = 5f; final float airMass = 1.2f;
            float[] b = new float[4096]; float[] a = new float[4096];
            char[] in = new char[4096]; java.util.Arrays.fill(in, AIR_IX);
            char[] out = new char[4096]; java.util.Arrays.fill(out, AIR_IX);
            java.util.Arrays.fill(b, airMass); java.util.Arrays.fill(a, airMass);
            in[0] = WATER_IX; out[0] = WATER_IX; b[0] = 1000f; a[0] = 1000f - wet * dm;
            for (int i = 1; i <= wet; i++) { in[i] = AIR_IX; out[i] = WATER_IX; a[i] = dm; }
            for (int i = wet + 1; i <= 2 * wet; i++) { in[i] = AIR_IX; out[i] = AIR_IX; a[i] = airMass + airMass; }
            cases.add(new Case("displace", a, b, in, out, true));
        }
        // (3) displacement swap (conserved)
        {
            final int swaps = 100; final float airMass = 1.2f;
            float[] b = new float[4096]; float[] a = new float[4096];
            char[] in = new char[4096]; char[] out = new char[4096];
            for (int s = 0; s < swaps; s++) {
                int x = 2 * s, y = 2 * s + 1;
                in[x] = WATER_IX; out[x] = AIR_IX;   b[x] = 1000f;   a[x] = airMass;
                in[y] = AIR_IX;   out[y] = WATER_IX; b[y] = airMass; a[y] = 1000f;
            }
            cases.add(new Case("swap", a, b, in, out, true));
        }
        // (4) over-cap boil parcel sheds to a steam neighbour (conserved, bound-exempt)
        {
            float[] b = new float[4096]; float[] a = new float[4096];
            char[] in = new char[4096]; java.util.Arrays.fill(in, STEAM_IX);
            char[] out = new char[4096]; java.util.Arrays.fill(out, STEAM_IX);
            b[0] = 1000f; a[0] = 999.6f; b[1] = 0.6f; a[1] = 1.0f;
            java.util.Arrays.fill(b, 2, 4096, 0.6f); java.util.Arrays.fill(a, 2, 4096, 0.6f);
            cases.add(new Case("overcap", a, b, in, out, true));
        }
        // (5) fabrication (rejected)
        {
            float[] b = new float[4096]; float[] a = new float[4096];
            char[] in = new char[4096]; java.util.Arrays.fill(in, WATER_IX);
            char[] out = new char[4096]; java.util.Arrays.fill(out, WATER_IX);
            java.util.Arrays.fill(b, 500f); java.util.Arrays.fill(a, 500f);
            a[0] = 1000f;
            cases.add(new Case("fabrication", a, b, in, out, false));
        }

        for (Case c : cases) {
            boolean perSection = StepValidator.massConservedPerSpecies(
                    c.after(), c.before(), c.in(), c.out(), perSpeciesLut());
            StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
            ledger.add(c.after(), c.before(), c.in(), c.out(), perSpeciesLut());
            boolean batch = ledger.conserved();
            assertEquals(c.expect(), perSection, "per-section verdict for " + c.name());
            assertEquals(perSection, batch, "single-entry ledger parity for " + c.name());
        }
    }

    // ---- Standalone per-cell bound check ----

    @Test
    void cellsWithinBoundRejectsIllegalOverCapButAllowsBoilParcel() {
        // The bound exemption is purely "over its own cap" (the transient boil parcel), so any over-cap
        // fluid cell is allowed. What the bound MUST still reject is a non-finite cell and a negative
        // cell (the lower bound is never relaxed by the over-cap exemption).
        char[] out = new char[4096]; java.util.Arrays.fill(out, STEAM_IX);

        // (a) a clean field at/under cap -> within bound.
        float[] ok = new float[4096]; java.util.Arrays.fill(ok, 0.6f);
        assertTrue(StepValidator.cellsWithinBound(ok, out, perSpeciesLut()));

        // (b) the boil over-cap parcel (1000 kg, way over 0.6) -> exempt, allowed.
        float[] boil = new float[4096]; java.util.Arrays.fill(boil, 0.6f);
        boil[0] = 1000f;
        assertTrue(StepValidator.cellsWithinBound(boil, out, perSpeciesLut()),
                "over-cap boil parcel is the documented exemption");

        // (c) a non-finite cell -> rejected.
        float[] nan = new float[4096]; java.util.Arrays.fill(nan, 0.6f);
        nan[0] = Float.NaN;
        assertFalse(StepValidator.cellsWithinBound(nan, out, perSpeciesLut()),
                "non-finite cell rejected");

        // (d) a negative cell -> rejected (lower bound never relaxed by the over-cap exemption).
        float[] neg = new float[4096]; java.util.Arrays.fill(neg, 0.6f);
        neg[0] = -5f;
        assertFalse(StepValidator.cellsWithinBound(neg, out, perSpeciesLut()),
                "negative mass rejected");
    }

    @Test
    void cellsWithinBoundBoundsAirToItsMaxMass() {
        // §11: air is now a tracked species, so an air output cell IS bounded to air's max_mass (1000).
        // A resting/compressed air cell within [0, 1000] passes; a NEGATIVE air cell (lower bound, never
        // relaxed by the over-cap exemption) fails.
        char[] out = new char[4096]; java.util.Arrays.fill(out, AIR_IX);
        float[] rest = new float[4096]; java.util.Arrays.fill(rest, 1.2f);
        assertTrue(StepValidator.cellsWithinBound(rest, out, perSpeciesLut()), "resting air within bound");
        float[] capped = new float[4096]; java.util.Arrays.fill(capped, 1.2f);
        capped[0] = 1000f;
        assertTrue(StepValidator.cellsWithinBound(capped, out, perSpeciesLut()), "air at its cap within bound");
        float[] neg = new float[4096]; java.util.Arrays.fill(neg, 1.2f);
        neg[0] = -5f;
        assertFalse(StepValidator.cellsWithinBound(neg, out, perSpeciesLut()),
                "negative air mass rejected (lower bound never relaxed)");
    }
}
