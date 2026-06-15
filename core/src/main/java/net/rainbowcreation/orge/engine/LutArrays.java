package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flat per-material LUT carrying EXACTLY the law §8 fixed schema, packed for the JNI registration
 * (the whole-region production path). One array per field, indexed by LUT slot.
 *
 * <p><b>v4 §1.2 / law §8 17-column schema</b> — the eight base physics floats
 * {@code cond, heatCap, molar, minMass, maxMass, visc, defaultMass, yieldStress}, the five v4 §1.2
 * radiation/EOS/latent floats {@code emissivity (ε), thermalExpansion (β), latentHeatMin,
 * latentHeatMax, tRefGas} (appended after {@code maxTarget} so the JNI call order — controlled in
 * {@code NativeEngine} — places them at the end), and the phase quadruple {@code minTemp, maxTemp}
 * (float thresholds) with {@code minTarget, maxTarget} (the target material's globally-stable
 * {@code matIx}, resolved from its id). The phase quadruple is kept IN the engine LUT (not Java) so a
 * future DECODE relabels locally, keeping enthalpy {@code E}.</p>
 *
 * <p>There is NO movability flag: immovability falls out of {@code visc == +∞} (spec invariant 1).
 * An absent viscosity is already {@link Float#POSITIVE_INFINITY} (frozen) on the {@link Material},
 * so packing it directly yields +∞. {@code yieldStress} is the law §8 threshold axis — {@code 0}
 * for all current fluids (present, deferred). A phase target that is {@code null} (no transition on
 * that side) or whose id is not in the LUT packs as {@link #NO_TARGET}.</p>
 *
 * <p>Slot 0 is the VACUUM sentinel (per spec invariant 5): {@code molar==0, minMass==0, maxMass==0}
 * with a <b>finite</b> viscosity so vacuum is the lightest <i>movable</i> fluid (displaceable, not
 * frozen). This falls out of the VACUUM material itself; {@code pack} does not special-case it.</p>
 */
public record LutArrays(float[] cond, float[] heatCap, float[] molar,
                        float[] minMass, float[] maxMass, float[] visc,
                        float[] defaultMass, float[] yieldStress,
                        float[] minTemp, float[] maxTemp,
                        int[] minTarget, int[] maxTarget,
                        float[] emissivity, float[] thermalExpansion,
                        float[] latentHeatMin, float[] latentHeatMax, float[] tRefGas,
                        int matCount) {

    /** No phase transition on that side: matches the engine {@code MAT_NO_TARGET} sentinel (0xFFFF). */
    public static final int NO_TARGET = 0xFFFF;

    public static LutArrays pack(List<Material> lut) {
        int m = lut.size();
        if (m == 0) throw new IllegalArgumentException("material LUT is empty");

        // The slot of a material in `lut` IS its globally-stable matIx (MaterialTable.ordered). Build
        // the reverse id->slot map so a phase target Identifier resolves to the matIx the engine stores.
        Map<Identifier, Integer> idToSlot = new HashMap<>(m * 2);
        for (int i = 0; i < m; i++) idToSlot.put(lut.get(i).id(), i);

        float[] cond = new float[m], heatCap = new float[m], molar = new float[m];
        float[] minMass = new float[m], maxMass = new float[m], visc = new float[m];
        float[] defaultMass = new float[m], yieldStress = new float[m];
        float[] minTemp = new float[m], maxTemp = new float[m];
        int[] minTarget = new int[m], maxTarget = new int[m];
        float[] emissivity = new float[m], thermalExpansion = new float[m];
        float[] latentHeatMin = new float[m], latentHeatMax = new float[m], tRefGas = new float[m];
        for (int i = 0; i < m; i++) {
            Material mat = lut.get(i);
            cond[i] = mat.thermalConductivity();
            heatCap[i] = mat.heatCapacity();
            molar[i] = mat.molarMass();
            minMass[i] = mat.minMass();
            maxMass[i] = mat.maxMass();
            // Absent viscosity already loads as +∞ ("frozen") on the Material, so this packs +∞
            // directly — immovability is visc == +∞, no separate flag.
            visc[i] = mat.viscosity();
            defaultMass[i] = mat.defaultMass();
            yieldStress[i] = mat.yieldStress();
            minTemp[i] = mat.minTemp();
            maxTemp[i] = mat.maxTemp();
            minTarget[i] = slotOf(idToSlot, mat.minTarget());
            maxTarget[i] = slotOf(idToSlot, mat.maxTarget());
            // v4 §1.2 radiation/EOS/latent columns; all absent => 0 on the Material.
            emissivity[i] = mat.emissivity();
            thermalExpansion[i] = mat.thermalExpansion();
            latentHeatMin[i] = mat.latentHeatMin();
            latentHeatMax[i] = mat.latentHeatMax();
            tRefGas[i] = mat.tRefGas();
        }
        return new LutArrays(cond, heatCap, molar, minMass, maxMass, visc, defaultMass, yieldStress,
                minTemp, maxTemp, minTarget, maxTarget,
                emissivity, thermalExpansion, latentHeatMin, latentHeatMax, tRefGas, m);
    }

    /** Resolve a phase-target id to its LUT slot ({@code matIx}); null / unknown ⇒ {@link #NO_TARGET}. */
    private static int slotOf(Map<Identifier, Integer> idToSlot, Identifier target) {
        if (target == null) return NO_TARGET;
        Integer slot = idToSlot.get(target);
        return slot != null ? slot : NO_TARGET;
    }
}
