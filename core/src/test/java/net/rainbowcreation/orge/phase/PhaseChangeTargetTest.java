package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1.4 oracle: phase change selects a MATERIAL (by material id); the block drawn is that
 * material's {@code representative_block} via a SEPARATE material → block lookup. The two ids
 * differ (material {@code orge:ice} ≠ block {@code minecraft:ice}); identity lives in the material.
 */
class PhaseChangeTargetTest {

    private static Identifier id(String ns, String path) { return Identifier.fromNamespaceAndPath(ns, path); }

    private static final Identifier WATER = id("orge", "water");
    private static final Identifier STEAM = id("orge", "steam");
    private static final Identifier ICE = id("orge", "ice");

    private static final Identifier STEAM_BLOCK = id("orge", "steam");      // steam's own block
    private static final Identifier ICE_BLOCK = id("minecraft", "ice");     // ice renders as vanilla ice

    private static Material water() {
        return Material.builder(WATER)
                .thermalConductivity(0.6f).heatCapacity(1000f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(293f).viscosity(0f)
                .maxTemp(373.15f).minTemp(273.15f)
                .maxTarget(STEAM).minTarget(ICE)
                .representativeBlock(id("minecraft", "water"))
                .build();
    }

    /** Fake registry: material id → Material (each with its own representative_block). */
    private static Function<Identifier, Optional<Material>> fakeRegistry() {
        Material steam = Material.builder(STEAM)
                .thermalConductivity(0.026f).heatCapacity(2000f).molarMass(0.018f)
                .defaultMass(0.6f).defaultTemperature(400f).viscosity(0f)
                .representativeBlock(STEAM_BLOCK)
                .build();
        Material ice = Material.builder(ICE)
                .thermalConductivity(2.2f).heatCapacity(2100f).molarMass(0.018f)
                .defaultMass(917f).defaultTemperature(263f)
                .representativeBlock(ICE_BLOCK)
                .build();
        Map<Identifier, Material> reg = Map.of(STEAM, steam, ICE, ice);
        return k -> Optional.ofNullable(reg.get(k));
    }

    @Test
    void boilingSelectsSteamMaterialThenRendersSteamRepresentativeBlock() {
        Optional<Identifier> target = PhaseRule.targetMaterial(400f, water());
        // identity = the MATERIAL id
        assertEquals(Optional.of(STEAM), target, "boiling selects the steam MATERIAL");
        // render = the SEPARATE material → representative_block lookup
        Optional<Identifier> block = PhaseRenderResolver.representativeBlock(fakeRegistry(), target.get());
        assertEquals(Optional.of(STEAM_BLOCK), block);
    }

    @Test
    void freezingSelectsIceMaterialThenRendersVanillaIceBlock() {
        Optional<Identifier> target = PhaseRule.targetMaterial(250f, water());
        assertEquals(Optional.of(ICE), target, "freezing selects the ice MATERIAL");
        Optional<Identifier> block = PhaseRenderResolver.representativeBlock(fakeRegistry(), target.get());
        assertEquals(Optional.of(ICE_BLOCK), block);
    }

    @Test
    void identityIsTheMaterialIdNotTheRenderedBlock() {
        // The whole point of the indirection: for ice the material id and the rendered block id DIFFER.
        Optional<Identifier> target = PhaseRule.targetMaterial(250f, water());
        Optional<Identifier> block = PhaseRenderResolver.representativeBlock(fakeRegistry(), target.get());
        assertNotEquals(target, block, "material orge:ice is NOT the block minecraft:ice");
        assertEquals(ICE, target.get(), "identity is the material id");
        assertEquals(ICE_BLOCK, block.get(), "the drawn block is a separate lookup");
    }

    @Test
    void absentTargetMaterialResolvesToEmpty() {
        Optional<Identifier> block =
                PhaseRenderResolver.representativeBlock(fakeRegistry(), id("orge", "unregistered"));
        assertTrue(block.isEmpty(), "an unregistered material has no representative block");
    }
}
