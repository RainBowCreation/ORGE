package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import java.util.List;

/**
 * Flat per-material LUT carrying EXACTLY the law §8 fixed-schema physics floats the unified fluid
 * engine needs, packed by {@link RegionMarshaller} (the whole-region production path). One array
 * per physics field, indexed by LUT slot. The §8 octet (issue #2):
 * {@code cond(thermal_conductivity), heatCap, molar, minMass, maxMass, visc, defaultMass(= EOS rest
 * density m₀), yieldStress(threshold; 0 for current fluids — present, deferred)}.
 *
 * <p>There is NO movability flag: immovability falls out of {@code visc == +∞} (spec invariant 1).
 * An absent viscosity is already {@link Float#POSITIVE_INFINITY} (frozen) on the {@link Material},
 * so packing it directly yields +∞. The legacy flag/fullMass/minFlow/gas/air arrays and the §11
 * air-density sentinel are gone.</p>
 *
 * <p>Slot 0 is the VACUUM sentinel (per spec invariant 5): {@code molar==0, minMass==0, maxMass==0}
 * with a <b>finite</b> viscosity so vacuum is the lightest <i>movable</i> fluid (displaceable, not
 * frozen). This falls out of the VACUUM material itself (see {@code MaterialLut.VACUUM}); {@code pack}
 * does not special-case it.</p>
 */
public record LutArrays(float[] cond, float[] heatCap, float[] molar,
                        float[] minMass, float[] maxMass, float[] visc,
                        float[] defaultMass, float[] yieldStress, int matCount) {

    public static LutArrays pack(List<Material> lut) {
        int m = lut.size();
        if (m == 0) throw new IllegalArgumentException("material LUT is empty");
        float[] cond = new float[m], heatCap = new float[m], molar = new float[m];
        float[] minMass = new float[m], maxMass = new float[m], visc = new float[m];
        float[] defaultMass = new float[m], yieldStress = new float[m];
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
            // Law §8 tail: defaultMass = EOS rest density m₀; yieldStress = threshold (0 for fluids).
            defaultMass[i] = mat.defaultMass();
            yieldStress[i] = mat.yieldStress();
        }
        return new LutArrays(cond, heatCap, molar, minMass, maxMass, visc,
                defaultMass, yieldStress, m);
    }
}
