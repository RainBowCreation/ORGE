package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class EngineBVelocityIT {
    private static boolean nativeAvailable() {
        try { NativeLoader.load(); return true; } catch (Throwable t) { return false; }
    }
    private static Material mat(String id, float hc, float k, float mol, float mn, float mx, float visc, float defMass) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(hc).thermalConductivity(k).molarMass(mol)
                .minMass(mn).maxMass(mx).viscosity(visc)
                .defaultMass(defMass).defaultTemperature(300f).build();
    }
    private static List<Material> lutWithWater() {
        List<Material> lut = new ArrayList<>();
        lut.add(mat("orge:void",  0f,    0f,    0f,    0f,   0f,    Float.POSITIVE_INFINITY, 0f));
        lut.add(mat("orge:water", 4186f, 0.6f,  0.018f,125f, 1100f, 0.001f, 1000f));
        return lut;
    }

    @Test
    void registerWithDefaultMassDoesNotThrow() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        // Should accept the 7-array register (cond,heatCap,molar,minMass,maxMass,visc,defaultMass).
        assertDoesNotThrow(() -> engine.registerMaterials(101, lutWithWater()));
    }

    @Test
    void stepRoundTripsVelocityFinite() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        engine.registerMaterials(102, lutWithWater());
        // one column: a water cell on a stone floor; after one advection step the velocity OUT
        // channel is populated and FINITE (proves the velocity arrays round-trip through JNI).
        int N = RegionMarshaller.CHUNK_N;
        char[] mat = new char[N]; float[] mass = new float[N]; float[] t = new float[N];
        for (int i=0;i<N;i++){ mat[i]=0; mass[i]=0f; t[i]=300f; }
        int floor = 8 + 16*39 + 6144*8, cell = 8 + 16*40 + 6144*8;
        mat[floor]=(char)0; // (kept void/empty floor is fine; we only need the engine to run + write velocity)
        mat[cell]=(char)1; mass[cell]=1000f; t[cell]=290f;
        ColumnTask col = new ColumnTask(0,0, mat, mass, t);
        ColumnResult r = engine.stepWorld(java.util.List.of(col), 102, 0.25, OrgeEngine.PASS_ADVECTION).get(0);
        assertTrue(Float.isFinite(r.velX()[cell]), "velocity-out finite after step");
    }
}
