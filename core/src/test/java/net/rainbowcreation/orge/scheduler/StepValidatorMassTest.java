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
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                1f, 1f, 0f, 1000f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, Float.NaN, false, true);
    }

    private static Material genericSolid() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "generic_solid"),
                1f, 1f, 0f, 2500f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null);
    }

    /** VOID=0, water=1, generic_solid=2. */
    private static List<Material> lut() {
        return List.of(MaterialLut.VOID, water(), genericSolid());
    }

    private static Material lava() {
        // canonical 17-arg ctor: fluid=true, minFlow=400, maxMass=3100, gas=false
        return new Material(Identifier.fromNamespaceAndPath("orge", "lava"),
                1f, 1f, 0f, 3100f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, Float.NaN, false, true, 400f, 3100f, false);
    }

    private static Material steam() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "steam"),
                1f, 1f, 0f, 0.6f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, Float.NaN, false, true, 0.6f, 0.6f, true);
    }

    private static Material air() {
        // Real orge:air: State.AIR (air()==true), NON-fluid, ~1.2 kg per cell. Built via the canonical
        // 16-arg ctor so the State.AIR path is taken (the compat ctors only fold into FLUID/SOLID/GAS).
        return new Material(Identifier.fromNamespaceAndPath("orge", "air"),
                1f, 1f, 0f, 1.2f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, Float.NaN, false, Material.State.AIR, 0f, 0f);
    }

    /** VOID=0, water=1, generic_solid=2, lava=3, steam=4, air=5. */
    private static List<Material> perSpeciesLut() {
        return List.of(MaterialLut.VOID, water(), genericSolid(), lava(), steam(), air());
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
    void wettingAnAirCellIsConserved() {
        // REGRESSION GUARD for the wetting defect: a water cell (1000 kg) donates 125 kg into an
        // adjacent AIR cell, which adopts the water species (air matIx 0 -> water). Dual-index sum:
        // water sumBefore = donor 1000 (recipient was air -> not counted in sumBefore); water sumAfter
        // = donor 875 + recipient 125 = 1000. Conserved -> gate returns true (the OLD output-indexed +
        // species-change-exempt design rejected this and froze the spread).
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
    void wettingIntoRealAirIsConserved() {
        // REGRESSION GUARD for the real-air wetting defect: with the real orge:air material a wetted
        // cell's INPUT mass is ~1.2 kg (not 0). The kernel ADOPTS that air into the fluid (cell becomes
        // water, absorbing the air's 1.2 kg + deposited dm; TOTAL mass conserved). §9 must credit that
        // 1.2 kg air 'before' to the OUTPUT water species. A single donor sheds dm into each of 100 air
        // cells. Old code (air 'before' dropped) left water sumAfter exceeding sumBefore by 100*1.2 =
        // 120 kg, far over the ε·N ≈ 40.96 kg tolerance -> false reject (the in-game re-freeze).
        final int wet = 100;
        final float dm = 5f;          // water deposited into each wetted air cell
        final float airMass = 1.2f;   // each air cell's adopted resting mass
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, AIR_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, AIR_IX);
        // background: a flat air field (air in == air out, before==after==1.2) -> contributes to neither sum.
        java.util.Arrays.fill(before, airMass);
        java.util.Arrays.fill(after, airMass);
        // donor water cell (index 0): full, sheds wet*dm kg into the wetted cells.
        inMat[0] = WATER_IX; outMat[0] = WATER_IX;
        before[0] = 1000f; after[0] = 1000f - wet * dm;
        // wetted cells [1..wet]: air-in, water-out; each adopts 1.2 air + dm deposit.
        for (int i = 1; i <= wet; i++) {
            inMat[i] = AIR_IX;          // air in (before == 1.2)
            outMat[i] = WATER_IX;       // water out (adopted)
            after[i] = airMass + dm;    // adopted air mass + deposited water
        }
        // water sumBefore = donor 1000 + 100*1.2 (air credited to water) = 1120
        // water sumAfter  = donor (1000-500) + 100*(1.2+5) = 500 + 620   = 1120  -> conserved.
        // Without the air-credit, sumBefore stays 1000 while sumAfter is 1120 -> off by 120 kg
        // (= 100 wetted cells * 1.2 air each), far over the ε·N ≈ 40.96 kg tolerance -> false reject.
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void densitySwapWithAirIsConserved() {
        // Steam below air rises (Plan-1 swap): lower cell steam(0.6) -> air(0), upper cell air(0) ->
        // steam(0.6). Dual-index: steam sumBefore = lower-in 0.6; steam sumAfter = upper-out 0.6. ✓
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; char[] outMat = new char[4096]; // all air (0) by default
        int lo = 0, up = 1;
        inMat[lo] = STEAM_IX; outMat[lo] = 0;        before[lo] = 0.6f; after[lo] = 0f;
        inMat[up] = 0;        outMat[up] = STEAM_IX;  before[up] = 0f;   after[up] = 0.6f;
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
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
        // air-in/water-out (it received the water). This is mass-conserving per species globally.
        //   Donor A : water-in 1000 -> air-out 1.2  (water displaced down, air risen up)
        //   Below B : air-in 1.2     -> water-out 1000 (water arrived)
        // Pre-fix accounting (BROKEN): B hits the air-absorb branch -> water sumBefore += 1.2 (B's before);
        //   A hits the fluid-in branch -> water sumBefore += 1000 (A's before); but A's OUTPUT air is non-
        //   fluid so it is NOT added to any sumAfter. -> water sumBefore overcounts by 1.2 PER SWAP. A
        //   single swap (1.2) hides under ε·N (≈40.96 kg), so we stage MANY swaps: 100 swaps -> 120 kg of
        //   phantom over-count, far over tolerance -> the pre-fix gate FALSE-rejects this valid result.
        // Post-fix: A (fluid-in/air-out) credits its OUTPUT air mass (1.2) to water's sumAfter, so each
        //   swap balances (water sumBefore += 1000+1.2, sumAfter += 1000(B)+1.2(A)) -> conserved.
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
    void wettingStillConserves() {
        // REGRESSION (no fluid-in/air-out cell): a donor water cell sheds dm into adjacent real-air cells
        // that ADOPT the water species (air-in/water-out). The new swap branch (fluid-in/air-out) never
        // fires here, so wetting accounting is unchanged: air's 1.2 'before' is credited to the output
        // water species and donor+recipients balance.
        final int wet = 100; final float dm = 5f; final float airMass = 1.2f;
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, AIR_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, AIR_IX);
        java.util.Arrays.fill(before, airMass);
        java.util.Arrays.fill(after, airMass);
        inMat[0] = WATER_IX; outMat[0] = WATER_IX;            // donor stays water-in/water-out (NOT air-out)
        before[0] = 1000f; after[0] = 1000f - wet * dm;
        for (int i = 1; i <= wet; i++) {
            inMat[i] = AIR_IX;   outMat[i] = WATER_IX;        // air -> water (wetted)
            after[i] = airMass + dm;
        }
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()),
                "pure wetting (no swap donor) must still conserve");
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
        // (2) wetting into real air (conserved)
        {
            final int wet = 100; final float dm = 5f; final float airMass = 1.2f;
            float[] b = new float[4096]; float[] a = new float[4096];
            char[] in = new char[4096]; java.util.Arrays.fill(in, AIR_IX);
            char[] out = new char[4096]; java.util.Arrays.fill(out, AIR_IX);
            java.util.Arrays.fill(b, airMass); java.util.Arrays.fill(a, airMass);
            in[0] = WATER_IX; out[0] = WATER_IX; b[0] = 1000f; a[0] = 1000f - wet * dm;
            for (int i = 1; i <= wet; i++) { in[i] = AIR_IX; out[i] = WATER_IX; a[i] = airMass + dm; }
            cases.add(new Case("wetting", a, b, in, out, true));
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
    void cellsWithinBoundIgnoresNonFluidOutputCells() {
        // cellsWithinBound only bounds FLUID-output cells. An air (non-fluid) output cell carrying an
        // absurd mass is not an advection mass and must not trip the bound.
        char[] out = new char[4096]; java.util.Arrays.fill(out, AIR_IX);
        float[] m = new float[4096]; java.util.Arrays.fill(m, 9_999_999f);
        assertTrue(StepValidator.cellsWithinBound(m, out, perSpeciesLut()),
                "non-fluid (air) output cells are not bound-checked");
    }
}
