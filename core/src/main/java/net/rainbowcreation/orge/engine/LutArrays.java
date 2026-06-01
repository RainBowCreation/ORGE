package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import java.util.List;

/** Flat per-material LUT arrays shared by BatchMarshaller (per-section, dormant) and RegionMarshaller
 *  (whole-region). Encapsulates the §11 air-flag flip and the slot-0 AIR_DENSITY label so both paths
 *  stay identical. */
public record LutArrays(float[] cond, float[] heatCap, float[] visc, float[] fullMass,
                        byte[] fluid, float[] minFlow, float[] maxMass, byte[] gas, byte[] air,
                        float[] molar, int matCount) {

    public static final float AIR_DENSITY = 1.2f;

    public static LutArrays pack(List<Material> lut) {
        int m = lut.size();
        if (m == 0) throw new IllegalArgumentException("material LUT is empty");
        float[] cond = new float[m], heatCap = new float[m], visc = new float[m], fullMass = new float[m];
        byte[] fluid = new byte[m], gas = new byte[m], air = new byte[m];
        float[] minFlow = new float[m], maxMass = new float[m], molar = new float[m];
        for (int i = 0; i < m; i++) {
            Material mat = lut.get(i);
            // TODO(Task 2.1): repack to six physics arrays, drop the flag arrays, marshal absent
            // viscosity as +∞, and change the orgeStepWorld JNI signature. For now keep the current
            // array shapes and source movability from the single movable() test.
            cond[i] = mat.thermalConductivity();
            heatCap[i] = mat.heatCapacity();
            visc[i] = mat.viscosity();
            fullMass[i] = mat.defaultMass();
            // Movability is now the single test (spec invariant 1): movable ⟺ viscosity finite.
            // The old fluid/gas/air flag trichotomy is gone from the record, so for this interim the
            // engine sees every movable material as a liquid (fluid=movable, gas/air=0). Gas buoyancy
            // and the air sink are reintroduced via the molar-mass-sorted advection in Task 3.x; the
            // flag arrays themselves are dropped in Task 2.1.
            byte mv = mat.movable() ? (byte) 1 : (byte) 0;
            fluid[i] = mv;
            minFlow[i] = mat.minMass();
            maxMass[i] = mat.maxMass();
            gas[i] = (byte) 0;
            air[i] = (byte) 0;
            molar[i] = mat.molarMass();
        }
        fullMass[0] = AIR_DENSITY; // VOID/ambient sentinel density label
        return new LutArrays(cond, heatCap, visc, fullMass, fluid, minFlow, maxMass, gas, air, molar, m);
    }
}
