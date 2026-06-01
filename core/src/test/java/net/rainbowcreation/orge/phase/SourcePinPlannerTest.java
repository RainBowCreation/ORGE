package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class SourcePinPlannerTest {

    private static Material pinned(float t) {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "lava"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(100f).defaultTemperature(t).pinned(true)
                .build();
    }
    private static Material plain() {
        return Material.builder(Identifier.fromNamespaceAndPath("orge", "water"))
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(100f).defaultTemperature(Float.NaN)
                .build();
    }

    @Test
    void pinnedNotTransitionedIsReset() {
        IntFunction<Material> cells = i -> (i == 5) ? pinned(1400f) : plain();
        List<SourcePinPlanner.Reset> r = SourcePinPlanner.plan(cells, List.of());
        assertEquals(1, r.size());
        assertEquals(5, r.get(0).cellIndex());
        assertEquals(1400f, r.get(0).temperatureK(), 1e-4f);
    }

    @Test
    void pinnedButTransitionedIsSkipped() {
        IntFunction<Material> cells = i -> (i == 5) ? pinned(1400f) : plain();
        List<SourcePinPlanner.Reset> r = SourcePinPlanner.plan(
                cells, List.of(new PhasePlanner.Transition(5,
                        Identifier.fromNamespaceAndPath("minecraft", "stone"))));
        assertTrue(r.isEmpty());
    }

    @Test
    void nonPinnedNeverReset() {
        IntFunction<Material> cells = i -> plain();
        assertTrue(SourcePinPlanner.plan(cells, List.of()).isEmpty());
    }
}
