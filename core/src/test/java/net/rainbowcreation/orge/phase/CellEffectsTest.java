package net.rainbowcreation.orge.phase;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CellEffects#pick}: which declared material effects a cell shows this step. */
class CellEffectsTest {

    private static Material mat(String path, String body) {
        return MaterialCodec.fromJson(Identifier.fromNamespaceAndPath("orge", path), JsonParser.parseString(body));
    }

    private static final Material WATER = mat("water", "{\"thermal_conductivity\": 0.6, \"heat_capacity\": 4186,"
            + " \"molar_mass\": 0.018, \"min_mass\": 125, \"default_mass\": 1000, \"max_mass\": 1000,"
            + " \"default_temperature\": 290, \"viscosity\": 0.001, \"max_temp\": 373, \"max_target\": \"orge:steam\","
            + " \"latent_heat_max\": 2.256e6, \"effects\": {"
            + "  \"suspended\": {\"particle\": \"minecraft:falling_water\", \"under_solid\": \"minecraft:dripping_water\"},"
            + "  \"boiling\": {\"particle\": \"minecraft:bubble_column_up\"}}}");
    private static final Material STEAM = mat("steam", "{\"thermal_conductivity\": 0.025, \"heat_capacity\": 2080,"
            + " \"molar_mass\": 0.018, \"min_mass\": 0.06, \"default_mass\": 0.6, \"max_mass\": 1000,"
            + " \"default_temperature\": 400, \"viscosity\": 1.3e-5,"
            + " \"effects\": {\"suspended\": {\"particle\": \"minecraft:cloud\", \"min_kg\": 0.06}}}");
    private static final Material AIR = mat("air", "{\"thermal_conductivity\": 0.026, \"heat_capacity\": 1005,"
            + " \"molar_mass\": 0.029, \"min_mass\": 1, \"default_mass\": 1.2, \"max_mass\": 1000,"
            + " \"default_temperature\": 290, \"viscosity\": 1.8e-5}");

    private static RepresentativeState.Component c(Material m, float kg) {
        return new RepresentativeState.Component(m, kg);
    }

    private static String type(CellEffects.Emit e) {
        return e.particle().getAsJsonObject().get("type").getAsString();
    }

    @Test
    void aTraceOfWaterInAirFallsAsDropsWithChanceByMass() {
        List<CellEffects.Emit> fx = CellEffects.pick(List.of(c(AIR, 1.2f), c(WATER, 25f)), 290f, true, false);
        assertEquals(1, fx.size());
        assertEquals("minecraft:falling_water", type(fx.get(0)));
        assertEquals(0.4f, fx.get(0).chance(), 1e-6f);   // 25 / (1000 / 16)
        assertFalse(fx.get(0).atTop());
    }

    @Test
    void underASolidTheTraceHangsAsADrip() {
        List<CellEffects.Emit> fx = CellEffects.pick(List.of(c(WATER, 200f)), 290f, true, true);
        assertEquals("minecraft:dripping_water", type(fx.get(0)));
        assertEquals(1f, fx.get(0).chance());
        assertTrue(fx.get(0).atTop());
    }

    @Test
    void steamShowsOnlyAboveItsMinKg() {
        assertTrue(CellEffects.pick(List.of(c(AIR, 1.2f), c(STEAM, 0.01f)), 300f, true, false).isEmpty(),
                "humid air shows nothing");
        List<CellEffects.Emit> fx = CellEffects.pick(List.of(c(AIR, 1.0f), c(STEAM, 0.3f)), 380f, true, false);
        assertEquals("minecraft:cloud", type(fx.get(0)));
    }

    private static final Material LAVA = mat("lava", "{\"thermal_conductivity\": 1.5, \"heat_capacity\": 1150,"
            + " \"molar_mass\": 0.065, \"min_mass\": 330, \"default_mass\": 2650, \"max_mass\": 2650,"
            + " \"default_temperature\": 1473, \"viscosity\": 500, \"effects\": {"
            + "  \"suspended\": {\"particle\": \"minecraft:falling_lava\", \"under_solid\": \"minecraft:dripping_lava\"}}}");

    @Test
    void steamBoiledIntoALavaFilmRisesOutOfTheLavaBlock() {
        // #87: water under a lava film boils into the film's free volume; the cell is drawn as lava, and the
        // steam (a gas, χ ≈ 1) still shows its cloud — the lava itself (χ = 0) shows no drip inside its block
        List<CellEffects.Emit> fx = CellEffects.pick(List.of(c(LAVA, 338f), c(STEAM, 0.07f)), 1460f, false, false);
        assertEquals(1, fx.size());
        assertEquals("minecraft:cloud", type(fx.get(0)));
        assertEquals(1f, fx.get(0).chance(), 1e-3f);
        assertTrue(CellEffects.pick(List.of(c(LAVA, 338f), c(STEAM, 0.01f)), 1460f, false, false).isEmpty(),
                "below its min_kg the steam shows nothing");
    }

    @Test
    void aLiquidDrawnAsItsBlockShowsNoSuspendedDrip() {
        assertTrue(CellEffects.pick(List.of(c(WATER, 900f)), 290f, false, false).isEmpty());
    }

    @Test
    void waterDrawnAsABlockBubblesOnlyAtItsBoilingPoint() {
        assertTrue(CellEffects.pick(List.of(c(WATER, 1000f)), 372.9f, false, false).isEmpty());
        List<CellEffects.Emit> fx = CellEffects.pick(List.of(c(WATER, 1000f)), 373f, false, false);
        assertEquals(1, fx.size());
        assertEquals("minecraft:bubble_column_up", type(fx.get(0)));
    }

    @Test
    void aMaterialWithoutEffectsShowsNothing() {
        assertTrue(CellEffects.pick(List.of(c(AIR, 1.2f)), 290f, true, false).isEmpty());
    }
}
