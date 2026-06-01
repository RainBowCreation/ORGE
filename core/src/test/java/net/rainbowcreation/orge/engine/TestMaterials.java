package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

/**
 * Test-only {@link Material} factories mirroring the live datapack roster, copied verbatim from the
 * constructor calls in {@code Section11LivePipelineReproTest} (lines ~70-89). Shared by the engine and
 * scheduler unit tests so the LUT matches the real pipeline.
 *
 * <p>LUT-slot convention used by callers: index 0 = void, 1 = water, 2 = air.</p>
 */
public final class TestMaterials {

    private static final Identifier WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier STEAM = Identifier.fromNamespaceAndPath("orge", "steam");
    private static final Identifier AIR   = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ICE   = Identifier.fromNamespaceAndPath("minecraft", "ice");
    private static final Identifier STONE = Identifier.fromNamespaceAndPath("minecraft", "stone");
    private static final Identifier VOIDID = Identifier.fromNamespaceAndPath("orge", "void");

    /** Live datapack roster: orge:air — state=air, min 0.001, default 1.2, max 1000, M 0.029. */
    public static Material air() {
        return new Material(AIR, 0.026f, 1005f, 0f, 1.2f, 0.029f,
                Float.POSITIVE_INFINITY, 0f, null, null, null,
                Float.NaN, false, Material.State.AIR, 0.001f, 1000f);
    }

    /** Live water: fluid, default/cap 1000, floor 125. */
    public static Material water() {
        return new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                373.15f, 273.15f, STEAM, ICE, null,
                Float.NaN, false, Material.State.FLUID, 125f, 1000f);
    }

    /** Inert solid (stone): a no-flow wall that is not an air sink. */
    public static Material stone() {
        return new Material(STONE, 1.0f, 840f, 0f, 2000f, 0f, 9999f, 0f, null, null, null);
    }

    public static Material voidMat() {
        return new Material(VOIDID, 0f, 0f, 0f, 0f, 0.018f, 9999f, 0f, null, null, null);
    }

    private TestMaterials() {}
}
