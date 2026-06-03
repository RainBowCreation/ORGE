package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

/**
 * Test-only {@link Material} factories mirroring the live datapack roster, copied verbatim from the
 * constructor calls in {@code Section11LivePipelineReproTest} (lines ~70-89). Shared by the engine and
 * scheduler unit tests so the LUT matches the real pipeline.
 *
 * <p>LUT-slot convention used by callers: index 0 = vacuum, 1 = water, 2 = air.</p>
 */
public final class TestMaterials {

    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");
    private static final Identifier AIR   = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ICE   = Identifier.fromNamespaceAndPath("minecraft", "ice");
    private static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");
    private static final Identifier VACUUMID = Identifier.fromNamespaceAndPath("orge", "vacuum");

    /**
     * Live datapack roster: orge:air — a movable finite gas. Mirrors the canonical {@code air.json}
     * (thermal_conductivity 0.026, heat_capacity 1005, <b>molar_mass 0.002</b>, default_mass 1.2,
     * <b>min_mass 1.0</b>, max_mass 1000). The molar mass is the load-bearing correction: at 0.002 air
     * is LIGHTER than water (M 0.018), so under the engine's molar-mass sort water SINKS below air and
     * air rises — the pre-fix 0.029 inverted that (air heavier ⇒ water floated). Viscosity is kept at
     * {@code 0f} (fastest movable) rather than the JSON's tiny 0.00002 so the live oracles settle in a
     * handful of cycles; the test exercises buoyancy ORDERING, which depends on molar mass, not on the
     * exact resistance.
     */
    public static Material air() {
        return Material.builder(AIR)
                .thermalConductivity(0.026f).heatCapacity(1005f).molarMass(0.002f)
                .defaultMass(1.2f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(1.0f).maxMass(1000f)
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

    /** Vacuum sentinel: 0/0/0 masses, FINITE viscosity ⇒ displaceable (matches MaterialLut.VACUUM). */
    public static Material voidMat() {
        return Material.builder(VACUUMID)
                .thermalConductivity(0f).heatCapacity(1f).molarMass(0f)
                .defaultMass(0f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(0f).maxMass(0f)
                .minTemp(0f).maxTemp(9999f)
                .build();
    }

    private TestMaterials() {}
}
