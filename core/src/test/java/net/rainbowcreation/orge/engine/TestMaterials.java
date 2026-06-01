package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

/**
 * Test-only {@link Material} factories mirroring the live datapack roster, copied verbatim from the
 * constructor calls in {@code Section11LivePipelineReproTest} (lines ~70-89). Shared by the engine and
 * scheduler unit tests so the LUT matches the real pipeline.
 *
 * <p>LUT-slot convention used by callers: index 0 = void, 1 = water, 2 = air.</p>
 */
public final class TestMaterials {

    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");
    private static final Identifier AIR   = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ICE   = Identifier.fromNamespaceAndPath("minecraft", "ice");
    private static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");
    private static final Identifier VOIDID = Identifier.fromNamespaceAndPath("orge", "void");

    /** Live datapack roster: orge:air — a movable finite gas, min 0.001, default 1.2, max 1000, M 0.029. */
    public static Material air() {
        return Material.builder(AIR)
                .thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.029f)
                .defaultMass(1.2f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(0.001f).maxMass(1000f)
                .minTemp(0f)
                .build();
    }

    /** Live water: movable, default/cap 1000, floor 125. */
    public static Material water() {
        return Material.builder(WATER)
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(125f).maxMass(1000f)
                .minTemp(273.15f).maxTemp(373.15f).maxTarget(STEAM).minTarget(ICE)
                .build();
    }

    /** Inert solid (stone): a no-flow wall (frozen ⇒ viscosity absent) that is not an air sink. */
    public static Material stone() {
        return Material.builder(STONE)
                .thermalConductivity(1.0f).heatCapacity(840f).molarMass(0f)
                .defaultMass(2000f).defaultTemperature(Float.NaN)
                .minTemp(0f).maxTemp(9999f)
                .build(); // no viscosity ⇒ +∞ (frozen)
    }

    /** Void sentinel: 0/0/0 masses, FINITE viscosity ⇒ displaceable (matches MaterialLut.VOID). */
    public static Material voidMat() {
        return Material.builder(VOIDID)
                .thermalConductivity(0f).heatCapacity(1f).molarMass(0f)
                .defaultMass(0f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(0f).maxMass(0f)
                .minTemp(0f).maxTemp(9999f)
                .build();
    }

    private TestMaterials() {}
}
