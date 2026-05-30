package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class SourcePinPlannerTest {

    private static Material pinned(float t) {
        return new Material(Identifier.fromNamespaceAndPath("orge", "lava"),
                1f, 1f, 0f, 100f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, t, true);
    }
    private static Material plain() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                1f, 1f, 0f, 100f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null);
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
