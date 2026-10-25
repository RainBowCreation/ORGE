package net.rainbowcreation.orge.block;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ThirstyFarmland} (issue #35): the source a hydrating farmland drinks from is the water block
 * vanilla's search finds; rain-triggered hydration drinks nothing.
 */
class ThirstyFarmlandTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final BlockPos FARMLAND = new BlockPos(10, 64, -20);

    private final List<BlockPos> requests = new ArrayList<>();

    private void recording() {
        ThirstyFarmland.setSink((dim, x, y, z) -> {
            assertEquals(DIM, dim);
            requests.add(new BlockPos(x, y, z));
        });
    }

    @AfterEach
    void uninstall() {
        ThirstyFarmland.setSink(null);
    }

    @Test
    void theSourceIsTheFirstWaterInVanillasSearchOrder() {
        // betweenClosed(pos + (−4,0,−4), pos + (4,1,4)) walks x fastest, then y, then z.
        BlockPos laterZ = FARMLAND.offset(-4, 0, 3);
        BlockPos upper = FARMLAND.offset(4, 1, -2);
        BlockPos lower = FARMLAND.offset(4, 0, -2);
        Set<BlockPos> water = Set.of(laterZ, upper, lower);
        assertEquals(lower, ThirstyFarmland.hydratingWater(FARMLAND, water::contains));
        assertEquals(upper, ThirstyFarmland.hydratingWater(FARMLAND, Set.of(laterZ, upper)::contains));
        assertNull(ThirstyFarmland.hydratingWater(FARMLAND,
                Set.of(FARMLAND.offset(5, 0, 0), FARMLAND.offset(0, 2, 0), FARMLAND.offset(0, -1, 0))::contains),
                "water outside the 9×2×9 box does not hydrate");
    }

    @Test
    void hydrationRequestsADrainFromTheSource() {
        recording();
        BlockPos source = FARMLAND.offset(-1, 0, 2);
        ThirstyFarmland.tick(DIM, FARMLAND, false, Set.of(source)::contains);
        assertEquals(List.of(source), requests);
    }

    @Test
    void hydrationDuringRainDrainsNothing() {
        recording();
        ThirstyFarmland.tick(DIM, FARMLAND, true, Set.of(FARMLAND.offset(-1, 0, 2))::contains);
        assertTrue(requests.isEmpty(), "rain hydrates for free, even beside water");
    }

    @Test
    void dryFarmlandOrNoSinkDrainsNothing() {
        recording();
        ThirstyFarmland.tick(DIM, FARMLAND, false, p -> false);
        assertTrue(requests.isEmpty(), "no water found: vanilla dries the farmland, nothing to drink");
        ThirstyFarmland.setSink(null);
        assertDoesNotThrow(() -> ThirstyFarmland.tick(DIM, FARMLAND, false, p -> true),
                "before ORGE installs its sink a tick is inert");
    }
}
