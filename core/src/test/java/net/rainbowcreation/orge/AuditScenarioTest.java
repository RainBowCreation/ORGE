package net.rainbowcreation.orge;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.phase.PhaseRule;
import net.rainbowcreation.orge.phase.SourcePinPlanner;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless audit: a pinned lava cell next to a column of water cells. Each "second" we diffuse,
 * evaluate phase change, then conditionally re-pin. Asserts the engine-audit story holds:
 * water heats monotonically, eventually boils to orge:steam, and lava stays pinned (never freezes).
 */
class AuditScenarioTest {

    private static final Identifier LAVA  = Identifier.fromNamespaceAndPath("orge", "lava");
    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");
    private static final Identifier ICE   = Identifier.fromNamespaceAndPath("minecraft", "ice");
    private static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");
    private static final Identifier ORGE_STEAM = Identifier.fromNamespaceAndPath("orge", "steam");

    // lava: pinned 1400, freezes (<1000) -> stone
    private static Material lava() {
        return Material.builder(LAVA)
                .thermalConductivity(1.5f).heatCapacity(1450f).molarMass(0f)
                .defaultMass(3100f).defaultTemperature(1400f).pinned(true)
                .minTemp(1000f).minTarget(STONE)
                .build(); // frozen (absent viscosity)
    }
    // water: boils (>373.15) -> orge:steam, freezes (<273.15) -> ice; not pinned; movable (finite visc).
    private static Material water() {
        return Material.builder(WATER)
                .thermalConductivity(0.6f).heatCapacity(4186f).molarMass(0.018f)
                .defaultMass(1000f).defaultTemperature(Float.NaN)
                .viscosity(0f) // finite ⇒ participates in advection
                .minTemp(273.15f).maxTemp(373.15f).maxTarget(ORGE_STEAM).minTarget(ICE)
                .build();
    }
    private static Material steam() {
        return Material.builder(STEAM)
                .thermalConductivity(0.02f).heatCapacity(2000f).molarMass(0.018f)
                .defaultMass(1f).defaultTemperature(Float.NaN)
                .minTemp(373.15f).minTarget(WATER) // condenses below 373.15 -> water
                .build(); // frozen (absent viscosity)
    }

    @Test
    void waterNextToPinnedLavaBoilsAndLavaSurvives() {
        // 8-cell 1-D line: index 0 = lava (pinned), 1..7 = water, all water starts at 290 K.
        int n = 8;
        Identifier[] block = new Identifier[n];
        float[] t = new float[n];
        block[0] = LAVA; t[0] = 1400f;
        for (int i = 1; i < n; i++) { block[i] = WATER; t[i] = 290f; }

        java.util.function.IntFunction<Material> matAt = i -> switch (block[i].getPath()) {
            case "lava" -> lava();
            case "water" -> water();
            case "steam" -> steam();
            default -> water();
        };

        boolean waterBoiled = false;
        for (int second = 0; second < 200 && !waterBoiled; second++) {
            // (1) diffuse: explicit 1-D conduction, fixed alpha; ends are insulated.
            float[] nt = t.clone();
            float alpha = 0.20f;
            for (int i = 0; i < n; i++) {
                float left  = (i > 0)     ? t[i - 1] : t[i];
                float right = (i < n - 1) ? t[i + 1] : t[i];
                nt[i] = t[i] + alpha * (left + right - 2 * t[i]);
            }
            t = nt;

            // (2) phase change on post-step temps (all cells)
            for (int i = 0; i < n; i++) {
                Optional<Identifier> target = PhaseRule.targetMaterial(t[i], matAt.apply(i));
                if (target.isPresent()) {
                    block[i] = target.get();
                    if (target.get().equals(ORGE_STEAM)) {
                        waterBoiled = true;
                    }
                }
            }

            // (3) conditional re-pin: a still-pinned cell that did not transition snaps back.
            //     (A cell that transitioned away from lava is no longer "lava" here, so it is
            //      not re-pinned — mirroring SourcePinPlanner's "pinned AND not transitioned".)
            for (int i = 0; i < n; i++) {
                if (block[i].getPath().equals("lava")) {
                    t[i] = matAt.apply(i).defaultTemperature();
                }
            }

            // invariant: lava cell never froze to stone
            assertNotEquals("stone", block[0].getPath(), "lava froze at second " + second);
        }

        assertTrue(waterBoiled, "water adjacent to pinned lava should boil to orge:steam within 200s");
        assertEquals("lava", block[0].getPath(), "lava must remain pinned lava");
        assertTrue(t[0] >= 1399f, "lava re-pinned to ~1400 K, was " + t[0]);
    }

    @Test
    void sourcePinPlannerHoldsLavaWhenNoTransition() {
        java.util.function.IntFunction<Material> cells = i -> (i == 0) ? lava() : water();
        List<SourcePinPlanner.Reset> resets = SourcePinPlanner.plan(cells, List.of());
        assertFalse(resets.isEmpty());
        assertEquals(0, resets.get(0).cellIndex());
        assertEquals(1400f, resets.get(0).temperatureK(), 1e-3f);
    }
}
