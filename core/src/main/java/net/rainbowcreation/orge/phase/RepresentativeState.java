package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

import java.util.List;

/**
 * The representative vanilla state of a multi-component cell (DESIGN-LAW v5 #7; proposal
 * {@code specs/2026-10-22-multi-material-cell-proposal.md} §6). Engine slots are unordered and carry no
 * "host" notion: the host is what the vanilla world already shows, the live block. Pure (no world), so it
 * is the test surface; {@link MinecraftFluidReconciler} resolves the cell's components once and applies it.
 *
 * <ol>
 *   <li><b>Host present</b> — the live block's material {@code H} is not drawn as a fluid and is among the
 *       cell's components: keep the live block. Its {@code waterlogged} property follows the water it holds,
 *       {@code v_water / V_pore} with hysteresis (on at ≥ {@value #WATERLOGGED_ON}, off below
 *       {@value #WATERLOGGED_OFF}). Every other property is untouched; a fluid in a block vanilla cannot
 *       waterlog stays invisible. Draining the water therefore never removes the stairs, slab or coral.</li>
 *   <li><b>Host absent</b> (melted, drained) or {@code H} is air/fluid: the cell is drawn as its condensed
 *       component with the largest volume {@code (1−χ)·m/ρ_p} — the existing level path
 *       ({@link FluidReconcileDecider}) then draws a fluid at {@code levelForFraction} and a solid as its
 *       block. With one component this is that component: today's behaviour.</li>
 * </ol>
 */
public final class RepresentativeState {

    /** Pore fill at or above which a host is drawn waterlogged. */
    public static final float WATERLOGGED_ON = 0.50f;
    /** Pore fill below which a host is drawn dry. Between the two the current state is kept. */
    public static final float WATERLOGGED_OFF = 0.25f;
    /** Mass at or below which a component is absent (the engine void floor). */
    static final float ABSENT_KG = 1e-6f;
    /** Pore volume [m³] below which a host holds no water (its water state is left as it is). */
    static final float NO_PORE_M3 = 1e-6f;
    private static final float CELL_M3 = 1f;
    private static final Identifier WATER_BLOCK = Identifier.fromNamespaceAndPath("minecraft", "water");

    /** One occupied slot: its material and mass [kg]. */
    public record Component(Material material, float massKg) {}

    private RepresentativeState() {}

    /** True when the live block's material is a non-fluid that is still among the cell's components. */
    public static boolean hostPresent(Material host, List<Component> components) {
        return host != null && !host.movable() && hostMass(host, components) > ABSENT_KG;
    }

    /**
     * The {@code waterlogged} value to draw for a present host, or null to leave the block as it is (no
     * host, or a host with no open volume, e.g. a full block on the fallback material).
     *
     * @param current the live block's current {@code waterlogged} value (kept inside the hysteresis band)
     */
    public static Boolean waterlogged(Material host, List<Component> components, boolean current) {
        if (!hostPresent(host, components)) {
            return null;
        }
        float pore = CELL_M3 - hostMass(host, components) / particleDensity(host);
        if (!(pore > NO_PORE_M3)) {
            return null;
        }
        float water = 0f;
        for (Component c : components) {
            if (drawsAsWater(c.material())) {
                water += c.massKg() / particleDensity(c.material());
            }
        }
        float fill = water / pore;
        if (fill >= WATERLOGGED_ON) return Boolean.TRUE;
        if (fill < WATERLOGGED_OFF) return Boolean.FALSE;
        return current;
    }

    /**
     * The component a host-absent cell is drawn as: the largest condensed volume {@code (1−χ)·m/ρ_p}
     * (first on a tie), or null for an empty cell.
     */
    public static Component dominant(List<Component> components) {
        Component best = null;
        float bestV = -1f;
        for (Component c : components) {
            if (!(c.massKg() > ABSENT_KG) || c.material() == null) continue;
            float v = (1f - chi(c.material())) * c.massKg() / particleDensity(c.material());
            if (v > bestV) {
                bestV = v;
                best = c;
            }
        }
        return best;
    }

    private static float hostMass(Material host, List<Component> components) {
        float m = 0f;
        for (Component c : components) {
            if (c.material() != null && c.material().id().equals(host.id())) {
                m += c.massKg();
            }
        }
        return m;
    }

    /** A fluid vanilla draws as water (the only fluid a block can be waterlogged with). */
    private static boolean drawsAsWater(Material m) {
        return m != null && m.movable() && WATER_BLOCK.equals(m.representativeBlock());
    }

    /** ρ_p with the law v5 #8 default ({@code = defaultMass}), as the engine reads it. */
    static float particleDensity(Material m) {
        return Math.max(Math.max(m.particleDensity(), m.defaultMass()), 1e-6f);
    }

    /** The compressibility weight χ = (max − default)/(max − min + ε), as the engine computes it. */
    static float chi(Material m) {
        float x = (m.maxMass() - m.defaultMass()) / (m.maxMass() - m.minMass() + 1e-6f);
        return Math.max(0f, Math.min(1f, x));
    }
}
