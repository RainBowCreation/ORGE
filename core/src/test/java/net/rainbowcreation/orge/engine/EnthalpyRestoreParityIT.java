package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T2 S5 — ABSOLUTE-E feed keystone (law §6: {@code E = m·h(T)} on the enthalpy curve is THE energy truth
 * carrier; {@code T} is a DERIVED diagnostic, never stored as the source of truth).
 *
 * <p><b>What S5 changed.</b> Before S5 {@code NativeEngine.stepWorld} RECONSTRUCTED the JNI {@code eIn}
 * channel from the persisted temperature as {@code eIn = mass·cp·T} — a single-slope linearisation that
 * LOSES energy across a latent-heat plateau (a cell pinned at the boil threshold {@code T*=373.15 K} that
 * has absorbed part of the {@code L=2.256 MJ/kg} latent heat carries {@code E} far ABOVE {@code m·cp·T*}).
 * S5 DELETES that reconstruction and feeds the engine the STORED absolute {@code E} straight from the
 * S4-threaded channel ({@code ColumnTask.enthalpy → RegionMarshaller.Flat.eIn → orgeStepWorld eIn}),
 * loss-free across the JNI seam — exactly like {@code vxIn = f.vxIn()}. The engine already treats
 * {@code E} as truth (orge_jni.cpp: {@code C->E[i] = eIn[i]; // NEVER reconstruct E from T}) and surfaces
 * the authoritative post-step energy in {@code eOut → ColumnResult.enthalpy} (10-arg slice).</p>
 *
 * <p><b>The keystone (this file's reason to exist).</b> The value the engine ingests is the STORED
 * {@code E}, not {@code mass·cp·T}. Proven against the real {@code .so} by seeding a cell whose stored
 * {@code E} sits MID-latent-plateau (above {@code m·cp·T*}) while its {@code temperature} channel carries
 * a deliberately WRONG, far-colder seed: if the engine still reconstructed {@code mass·cp·T} the surfaced
 * energy/temperature would track that cold seed; instead it tracks the stored mid-plateau {@code E}. The
 * energy {@code mass·cp·T} would have lost is the {@code ~1.128e6·m J} latent slug measured below.</p>
 */
@Tag("integration")
class EnthalpyRestoreParityIT {

    private static final int N = RegionMarshaller.CHUNK_N;
    private static final char L_VOID = 0, L_WATER = 1, L_STEAM = 2, L_AIR = 3;

    /** Latent-plateau LUT: water boils at 373.15 K (L=2.256e6) → steam (target present so the engine's
     *  boil-plateau guard fires); air fills the column; void at slot 0. */
    private static final List<Material> LAT_LUT = List.of(
            TestMaterials.voidMat(), TestMaterials.waterWithLatent(), TestMaterials.steam(), TestMaterials.air());
    private static final int LAT_EPOCH = 42;
    /** passes=0: no engine motion (step_world_b is skipped) so the ONLY thing exercised is the JNI FEED
     *  + DECODE — isolating "does eIn carry the stored E or a cp·T reconstruction?" with no advection/
     *  conduction confounder. The world still loads E=eIn and reads back eOut=E, tOut=derive_T(E,..). */
    private static final int PASSES = 0;

    private static final float WATER_CP = 4186f;      // J/(kg·K) — TestMaterials.waterWithLatent()
    private static final float BOIL_TSTAR = 373.15f;  // K        — water maxTemp (boil threshold)
    private static final float BOIL_L = 2.256e6f;     // J/kg     — latentHeatMax

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    private static int idx(int x, int engineY, int z) {
        return x + 16 * engineY + 6144 * z;
    }

    /**
     * KEYSTONE — the engine ingests the STORED absolute E, NOT {@code mass·cp·T}.
     *
     * <p>The water cell's stored {@code E} is placed MID-boil-plateau in the engine's own single-slope
     * decode frame ({@code orge_jni.cpp} store path: {@code T = E/(mass·cp)}): {@code E = m·(cp·T* + L/2)},
     * i.e. {@code E/(m·cp) = T* + L/(2cp) ≈ 642.6 K}, ABOVE the boil threshold — the cell carries half the
     * latent slug. Its {@code temperature} channel carries a deliberately WRONG cold seed (285 K), so the
     * two feeds disagree by a known amount. The retired {@code eIn = mass·cp·T} reconstruction would have
     * fed the engine {@code m·cp·285} and surfaced a ~285 K cell; the S5 stored-E feed makes the engine
     * surface the mid-plateau energy unchanged — {@code eOut == storedE} and the derived
     * {@code T = E/(m·cp) ≈ 642.6 K}. The gap {@code storedE − m·cp·T* = L/2·m = 1.128e6·m J} is exactly
     * the latent slug the single-slope-of-seed path destroyed.</p>
     */
    @Test
    void engineIngestsStoredAbsoluteENotMassCpT() {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(LAT_EPOCH, LAT_LUT);

        final int cell = idx(8, 102, 8);
        final float mass = 1000f;

        // Mid-boil-band absolute E in the engine's single-slope decode frame: E = m·(cp·T* + L/2).
        double midEta = (double) WATER_CP * BOIL_TSTAR + (double) BOIL_L / 2.0; // specific enthalpy [J/kg]
        double storedE = (double) mass * midEta;             // ABSOLUTE E [J] the cell actually holds

        // The WRONG, far-colder seed temperature. mass·cp·T from THIS seed would badly undershoot storedE.
        final float coldSeedT = 285f;
        double massCpColdSeed = (double) mass * WATER_CP * coldSeedT;   // what the retired path would feed
        // The latent slug = storedE − m·cp·T* = L/2·m: the energy a single-slope-of-T path loses when a
        // cell is mid-plateau. Pin the number the brief calls out (1.128e6·m J for water at L/2).
        double latentSlug = storedE - (double) mass * WATER_CP * BOIL_TSTAR;
        assertEquals((double) BOIL_L / 2.0 * mass, latentSlug, 1.0,
                "mid-band stored E sits L/2·m = 1.128e6·m J above m·cp·T* — the slug the old cp·T path lost");

        // Build the column: air everywhere, one water cell whose ENTHALPY channel carries the mid-plateau
        // absolute E and whose TEMPERATURE channel carries the wrong cold seed (proves T is NOT the source).
        char[] matIx = new char[N];
        float[] m = new float[N];
        float[] t = new float[N];
        float[] e = new float[N];
        for (int i = 0; i < N; i++) { matIx[i] = L_AIR; m[i] = 1.2f; t[i] = 285f; }
        matIx[cell] = L_WATER; m[cell] = mass; t[cell] = coldSeedT; e[cell] = (float) storedE;

        // 11-arg ColumnTask: enthalpy channel supplied (the S4 stored-E channel S5 now feeds to eIn).
        float[] z = new float[N];
        ColumnTask seed = new ColumnTask(0, 0, matIx, m, t, z.clone(), z.clone(), z.clone(), z.clone(),
                new float[N], e);

        ColumnResult r1 = engine.stepWorld(List.of(seed), LAT_EPOCH, 0.25, PASSES).get(0);

        // (1) The returned absolute E is the STORED mid-plateau E — NOT m·cp·coldSeed.
        double eOut = r1.enthalpy()[cell];
        assertEquals(storedE, eOut, 1e-3 * storedE,
                "eOut carries the STORED absolute E (loss-free); the retired m·cp·T feed would have "
                        + "surfaced m·cp·285 = " + massCpColdSeed + " J, off by the latent slug");
        assertTrue(Math.abs(eOut - massCpColdSeed) > 1e8,
                "eOut is far from the cold-seed m·cp·T energy (gap > 1e8 J): the engine read stored E, "
                        + "not a cp·T reconstruction of the seed temperature");

        // (2) The derived T is the linearised mid-band value E/(m·cp) ≈ 642.6 K — i.e. the engine decoded
        //     T FROM the stored E, NOT from the cold seed (which would have left ~285 K).
        float tOut = r1.temperature()[cell];
        float expectedLinearT = (float) (eOut / ((double) mass * WATER_CP));
        assertEquals(expectedLinearT, tOut, 1f,
                "derived T = E/(m·cp) decoded from the STORED mid-plateau E (≈642.6 K)");
        assertTrue(tOut > BOIL_TSTAR + 100f,
                "derived T is far ABOVE the cold seed (285 K) — the carrier is stored E, not cp·T·seed");

        // (3) passes=0: no step ran, so matIx/mass pass through unchanged — the read-back is the pure
        //     FEED+DECODE of the stored E, with no advection/conduction confounding the carrier proof.
        assertEquals(L_WATER, r1.matIx()[cell], "matIx passes through (no step at passes=0)");
        assertEquals(mass, r1.mass()[cell], 1e-2f, "mass passes through (no step at passes=0)");
    }

    /**
     * Off-plateau sanity: a plain warm water cell's stored E round-trips through the engine loss-free, and
     * the engine's single-slope decode {@code T = E/(m·cp)} reproduces the encoded temperature — the S5
     * feed introduces no cp·T artefact off the plateau either, where stored E and {@code m·cp·T} coincide.
     */
    @Test
    void offPlateauStoredERoundTrips() {
        NativeEngine engine = engineOrSkip();
        engine.registerMaterials(LAT_EPOCH, LAT_LUT);

        final int cell = idx(8, 102, 8);
        final float mass = 1000f;
        final float warmT = 350f; // strictly inside [273.15, 373.15] — off any plateau
        double storedE = (double) mass * WATER_CP * warmT; // E = m·cp·T (single-slope frame, off-plateau)

        char[] matIx = new char[N];
        float[] m = new float[N];
        float[] t = new float[N];
        float[] e = new float[N];
        for (int i = 0; i < N; i++) { matIx[i] = L_AIR; m[i] = 1.2f; t[i] = 285f; }
        matIx[cell] = L_WATER; m[cell] = mass; t[cell] = warmT; e[cell] = (float) storedE;
        float[] z = new float[N];
        ColumnTask seed = new ColumnTask(0, 0, matIx, m, t, z.clone(), z.clone(), z.clone(), z.clone(),
                new float[N], e);

        ColumnResult r1 = engine.stepWorld(List.of(seed), LAT_EPOCH, 0.25, PASSES).get(0);

        assertEquals(storedE, r1.enthalpy()[cell], 1e-3 * storedE, "off-plateau stored E surfaces in eOut");
        // E/(m·cp) over water's single slope returns the seeded warm T (off-plateau the linear decode == T).
        assertEquals(warmT, r1.temperature()[cell], 1f, "off-plateau derived T == the seeded warm T");
        assertEquals(L_WATER, r1.matIx()[cell], "matIx passes through (no step at passes=0)");
    }
}
