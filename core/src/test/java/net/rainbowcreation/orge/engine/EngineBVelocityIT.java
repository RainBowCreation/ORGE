package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

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
}
