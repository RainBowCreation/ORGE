package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockMaterialRuleTest {
    private static Identifier id(String ns, String p) { return Identifier.fromNamespaceAndPath(ns, p); }

    private MaterialRegistry registryWith(String... paths) {
        MaterialRegistry r = new MaterialRegistry();
        for (String p : paths) {
            r.put(Material.builder(id("orge", p))
                    .thermalConductivity(1f).heatCapacity(1f).molarMass(1f)
                    .defaultMass(1f).defaultTemperature(290f).build());
        }
        return r;
    }

    @Test void mapsMatchingPathToOrge() {
        MaterialRegistry r = registryWith("stone", "water", "air", "generic_solid");
        assertEquals(id("orge", "stone"), BlockMaterialRule.firstTouch(id("minecraft", "stone"), r));
        assertEquals(id("orge", "water"), BlockMaterialRule.firstTouch(id("minecraft", "water"), r));
        assertEquals(id("orge", "air"),   BlockMaterialRule.firstTouch(id("minecraft", "air"),   r));
    }

    @Test void missFallsBackToGenericSolid() {
        MaterialRegistry r = registryWith("stone", "generic_solid");
        assertEquals(id("orge", "generic_solid"),
                BlockMaterialRule.firstTouch(id("minecraft", "diamond_ore"), r));
    }

    @Test void droppsNamespaceForModdedBlocks() {
        MaterialRegistry r = registryWith("copper", "generic_solid");
        assertEquals(id("orge", "copper"),
                BlockMaterialRule.firstTouch(id("somemod", "copper"), r));
    }
}
