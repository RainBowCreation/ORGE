package net.rainbowcreation.orge;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.ColumnResult;
import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NativeLoader;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.engine.TestMaterials;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.scheduler.ColumnAssembler;
import net.rainbowcreation.orge.scheduler.MaterialLut;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * [DEBUG-tick] Isolates the post-/orge-set relaxation: does the NATIVE engine preserve an immovable,
 * non-conductive bedrock cell's enthalpy across one CONDUCTION|ADVECTION step? If eOut ≈ eIn the engine
 * is innocent and the 290 re-seed is Java-side; if it drifts, the engine resets it.
 */
@Tag("integration")
class BedrockTickPreservesETest {

    static final Identifier VOIDID = Identifier.fromNamespaceAndPath("orge", "vacuum");
    static final Identifier AIR = Identifier.fromNamespaceAndPath("orge", "air");
    static final Identifier BEDROCK = Identifier.fromNamespaceAndPath("orge", "bedrock");
    static final char VOID = 0, AIRX = 1, BEDX = 2;

    static Material voidMat() {
        return Material.builder(VOIDID).thermalConductivity(0f).heatCapacity(1f).molarMass(0f)
                .defaultMass(0f).defaultTemperature(Float.NaN).viscosity(0f).minMass(0f).maxMass(0f).build();
    }
    static Material air() {
        return Material.builder(AIR).thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.002f)
                .defaultMass(1.2f).defaultTemperature(Float.NaN).viscosity(0f).minMass(1.0f).maxMass(1000f).build();
    }
    static Material bedrock() {
        return Material.builder(BEDROCK).thermalConductivity(0f).heatCapacity(800f).molarMass(0.065f)
                .defaultMass(3000f).defaultTemperature(290f)
                .viscosity(Float.POSITIVE_INFINITY).minMass(3000f).maxMass(3000f).build();
    }

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge: " + t.getMessage());
        }
        return new NativeEngine();
    }

    @Test
    void engineKeepsBedrockEnthalpyAcrossOneStep() {
        NativeEngine e = engineOrSkip();
        List<Material> LUT = List.of(voidMat(), air(), bedrock());
        MaterialLut lutM = TestMaterials.lutOf(LUT);
        MaterialRegistry reg = TestMaterials.registryOf(LUT);
        Function<Identifier, Material> lookup = id -> reg.get(id).orElse(null);

        final int SEC = 4096;
        float bedE = (float) EnthalpyCurve.cellE(3000f, bedrock(), lookup, 300f); // 300 K target
        float airE = (float) EnthalpyCurve.cellE(1.2f, air(), lookup, 290f);

        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[SEC];
            float[] mass = new float[SEC];
            float[] temp = new float[SEC];
            Identifier[] stored = new Identifier[SEC];
            float[] enth = new float[SEC];
            Arrays.fill(mat, AIRX);
            Arrays.fill(mass, 1.2f);
            Arrays.fill(temp, 290f);
            Arrays.fill(stored, AIR);
            Arrays.fill(enth, airE);
            if (sectionY == -4) { // y=-64 cell at x=8,z=8 -> si = 8 + 256*8
                int si = 8 + 256 * 8;
                mat[si] = BEDX; mass[si] = 3000f; temp[si] = 300f; stored[si] = BEDROCK; enth[si] = bedE;
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp, new char[SEC], stored,
                    new float[SEC], new float[SEC], new float[SEC], new float[SEC], new float[SEC], enth);
        };

        ColumnTask task = ColumnAssembler.assemble(0, 0, lutM, reg, src);
        int ci = RegionMarshaller.colIdx(8, 0, 8); // engineY = -64 + 64 = 0

        e.registerMaterials(1, LUT);
        List<ColumnResult> res = e.stepWorld(List.of(task), 1, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION);
        ColumnResult r = res.get(0);
        float outE = r.enthalpy()[ci];

        assertEquals(bedE, outE, Math.abs(bedE) * 0.01f,
                "engine must preserve an immovable non-conductive bedrock cell's enthalpy across one step");
    }
}
