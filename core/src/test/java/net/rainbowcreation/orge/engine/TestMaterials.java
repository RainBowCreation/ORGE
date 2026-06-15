package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.scheduler.MaterialLut;

import java.util.List;

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

    /**
     * Water WITH the v4 §1.2 latent plateaus wired in (freeze L=3.34e5 J/kg at 273.15 K → ice; boil
     * L=2.256e6 J/kg at 373.15 K → steam) — same id as {@link #water()}. Kept separate from
     * {@link #water()} so the existing off-plateau golden-parity suites are untouched; used only by
     * {@code EnthalpyRestoreParityIT.midPlateauCellRoundTrip}, where a cell whose specific enthalpy
     * η=E/m sits inside [h(373.15), h(373.15)+L] must derive T pinned at 373.15 K (the boil plateau).
     */
    public static Material waterWithLatent() {
        return Material.builder(WATER)
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(125f).maxMass(1000f)
                .minTemp(273.15f).maxTemp(373.15f).maxTarget(STEAM).minTarget(ICE)
                .latentHeatMin(3.34e5f).latentHeatMax(2.256e6f)
                .build();
    }

    /**
     * Live steam (v4 §1.2): the boil-plateau {@code maxTarget} of {@link #waterWithLatent()}. Present
     * in the latent test LUT only so the engine's boil-plateau guard ({@code maxTarget < lut.size()})
     * fires — without a resolvable target the inverse-curve plateau is skipped. A finite gas band so it
     * is a legal substance; its own thermal data is irrelevant to the mid-plateau characterization.
     */
    public static Material steam() {
        return Material.builder(STEAM)
                .thermalConductivity(0.025f).heatCapacity(2080f).molarMass(0.018f)
                .defaultMass(0.6f).defaultTemperature(Float.NaN)
                .viscosity(0f).minMass(0.06f).maxMass(1000f)
                .minTemp(373.15f).minTarget(WATER).latentHeatMin(2.256e6f)
                .tRefGas(373f)
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

    /**
     * Builds a read-only {@link MaterialLut} VIEW whose slot order matches {@code slots}
     * (index 0 = vacuum sentinel), for tests that previously relied on first-seen append order.
     * The order is PRESERVED as given (NOT id-sorted) so existing hard-coded slot expectations stay
     * valid. Any {@code orge:vacuum}-id entry collapses onto the canonical slot-0
     * {@link net.rainbowcreation.orge.material.MaterialTable#VACUUM} sentinel; the rest follow in order.
     */
    public static MaterialLut lutOf(List<Material> slots) {
        java.util.List<Material> ordered = new java.util.ArrayList<>();
        ordered.add(net.rainbowcreation.orge.material.MaterialTable.VACUUM);
        for (Material m : slots) {
            if (m.id().equals(net.rainbowcreation.orge.material.MaterialTable.VACUUM.id())) {
                continue; // reuse the canonical slot-0 sentinel
            }
            ordered.add(m);
        }
        java.util.List<Material> immut = java.util.List.copyOf(ordered);
        return new MaterialLut(immut, net.rainbowcreation.orge.material.MaterialTable.slots(immut));
    }

    /**
     * A {@link MaterialRegistry} populated with {@code slots} plus a {@code generic_solid} fallback.
     * Used as the durable-material resolution registry in tests; harmless when no stored material is
     * exercised (storedMaterial all-null).
     */
    public static MaterialRegistry registryOf(List<Material> slots) {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(Material.builder(MaterialRegistry.FALLBACK_ID)
                .thermalConductivity(1.0f).heatCapacity(840f).molarMass(0f)
                .defaultMass(2000f).defaultTemperature(Float.NaN)
                .minTemp(0f).maxTemp(9999f)
                .build());
        for (Material m : slots) {
            reg.put(m);
        }
        return reg;
    }

    private TestMaterials() {}
}
