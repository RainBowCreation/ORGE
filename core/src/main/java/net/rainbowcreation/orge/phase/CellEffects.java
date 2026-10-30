package net.rainbowcreation.orge.phase;

import com.google.gson.JsonElement;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialEffects;

import java.util.ArrayList;
import java.util.List;

/**
 * Which material effects ({@link MaterialEffects}) a cell shows this step. Pure (no world): the reconciler
 * hands it the cell's composition, temperature and drawn block, and sends what it returns.
 */
public final class CellEffects {

    /** One effect to show: the particle JSON, the chance to show it this step, and whether it hangs at the cell's top. */
    public record Emit(JsonElement particle, MaterialEffects.Effect effect, float chance, boolean atTop) {}

    private CellEffects() {}

    /**
     * @param comps      the cell's components after this step
     * @param T          the cell's temperature [K]
     * @param drawnAir   the cell is drawn as air
     * @param solidAbove the block right above is a solid (not air, not a fluid)
     */
    public static List<Emit> pick(List<RepresentativeState.Component> comps, float T, boolean drawnAir,
                                  boolean solidAbove) {
        List<Emit> out = new ArrayList<>(2);
        for (RepresentativeState.Component c : comps) {
            Material m = c.material();
            MaterialEffects fx = m.effects();
            if (fx == null || fx == MaterialEffects.NONE) continue;
            MaterialEffects.Effect s = fx.suspended();
            // A cell drawn as air shows every suspended component. A cell drawn as a block shows a component by
            // its compressibility weight χ: a gas is never what the block draws (steam boiled into a lava film
            // rises out of the lava, #87), a condensed component is the block itself.
            float shown = drawnAir ? 1f : RepresentativeState.chi(m);
            if (shown > 0f && s != null && c.massKg() > 0f && c.massKg() >= s.minKg()) {
                JsonElement p = solidAbove && s.underSolid() != null ? s.underSolid() : s.particle();
                float trace = FluidReconcileLogic.TRACE_FRACTION * m.defaultMass();   // the most a trace holds
                float chance = shown * (trace > 0f ? Math.min(1f, c.massKg() / trace) : 1f);
                out.add(new Emit(p, s, chance, solidAbove));
            }
            MaterialEffects.Effect b = fx.boiling();
            if (!drawnAir && b != null && c.massKg() >= b.minKg() && T >= m.maxTemp()) {
                out.add(new Emit(b.particle(), b, 1f, false));
            }
        }
        return out;
    }
}
