package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CellReadoutTest {

    private static Identifier id(String ns, String path) {
        return Identifier.fromNamespaceAndPath(ns, path);
    }

    @Test
    void shortensTheTwoKnownNamespacesAndKeepsAnyOther() {
        assertEquals("M:sand", CellReadout.id(id("minecraft", "sand")));
        assertEquals("O:steam", CellReadout.id(id("orge", "steam")));
        assertEquals("create:andesite_alloy", CellReadout.id(id("create", "andesite_alloy")));
    }

    @Test
    void oneComponentCellIsOneMaterialWithItsMass() {
        assertEquals("M:sand 288.1K O:sand:1500 A", CellReadout.line(id("minecraft", "sand"), 288.15f,
                List.of(new SectionView.Component(id("orge", "sand"), 1500f)), LiveStatus.ACTIVE));
    }

    @Test
    void multiComponentCellListsEveryMaterialHeaviestFirst() {
        assertEquals("M:sand 288.1K O:sand:1500 O:steam:0.005712 D", CellReadout.line(id("minecraft", "sand"), 288.15f,
                List.of(new SectionView.Component(id("orge", "steam"), 0.0057123f),
                        new SectionView.Component(id("orge", "sand"), 1500f)), LiveStatus.DORMANT));
    }

    @Test
    void cellWithNoDataShowsADashForTemperatureAndTheMaterialWithoutMass() {
        assertEquals("M:sand - O:sand U", CellReadout.line(id("minecraft", "sand"), Float.NaN,
                List.of(new SectionView.Component(id("orge", "sand"), Float.NaN)), LiveStatus.UNLOADED));
    }

    @Test
    void everyStatusHasItsOwnLetter() {
        assertEquals("A", CellReadout.status(LiveStatus.ACTIVE));
        assertEquals("D", CellReadout.status(LiveStatus.DORMANT));
        assertEquals("U", CellReadout.status(LiveStatus.UNLOADED));
        assertEquals("B", CellReadout.status(LiveStatus.AMBIENT));
    }
}
