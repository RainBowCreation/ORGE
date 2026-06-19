package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.command.SectionView;
import net.rainbowcreation.orge.command.ServerStoreReadSource;
import net.rainbowcreation.orge.material.BlockMaterialRule;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DIAGNOSTIC REPRO (2026-06-19) — "after breaking a block, /orge get still shows orge:air @ 0 kg".
 *
 * <p>This test isolates WHERE the displayed {@code orge:air@0} comes from, distinguishing a
 * FUNCTIONAL bug (the cell is really still air/sub-min so fluids cannot flow in) from a
 * DISPLAY-ONLY bug (the cell is genuinely {@code orge:vacuum} in the sim, but the {@code /orge get}
 * material label is rendered from the LIVE Minecraft block — now {@code minecraft:air} → mapped to
 * {@code orge:air} — instead of from the stored per-cell material).</p>
 *
 * <p>It drives the EXACT in-game break mutation ({@link MinecraftThermalWorld#captureBreak}) against
 * the real §5 {@link SectionStore}, then inspects BOTH sources the {@code /orge get} line is built
 * from:</p>
 * <ul>
 *   <li>the STORED per-cell material ({@link SectionStore#materialAt}) — the functional/sim truth;</li>
 *   <li>the DISPLAY material the {@code /orge get} adapter actually prints
 *       ({@code OrgeCommands.liveCellDescriptor} → {@link net.rainbowcreation.orge.scheduler.LiveMaterials#materialFor}
 *       on the post-break {@code minecraft:air} block) — what the player SEES.</li>
 * </ul>
 */
class BreakAirGhostDisplayReproIT {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier ORGE_VACUUM = Identifier.fromNamespaceAndPath("orge", "vacuum");
    private static final Identifier ORGE_AIR = Identifier.fromNamespaceAndPath("orge", "air");
    private static final Identifier ORGE_WATER = Identifier.fromNamespaceAndPath("orge", "water");
    private static final Identifier MC_AIR = Identifier.fromNamespaceAndPath("minecraft", "air");

    private static Material fluid(Identifier id, float defaultMass) {
        return Material.builder(id)
                .thermalConductivity(1f).heatCapacity(1f).molarMass(0f)
                .defaultMass(defaultMass).defaultTemperature(Float.NaN).viscosity(0f).build();
    }

    private static SectionStoreManager loadedManager(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0);
        return mgr;
    }

    /** A registry with orge:air + orge:water so {@link BlockMaterialRule#firstTouch} resolves
     *  {@code minecraft:air → orge:air} exactly as the live {@code /orge get} display path does. */
    private static MaterialRegistry registryWithAirAndWater() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.put(fluid(ORGE_AIR, 1.2f));
        reg.put(fluid(ORGE_WATER, 1000f));
        return reg;
    }

    /**
     * The repro. Break a (water) cell via the in-game capture path, then read the two material
     * sources the {@code /orge get} line is composed from.
     */
    @Test
    void afterBreak_storeIsVacuum_butDisplayPathRendersAirFromTheLiveBlock(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SectionStore store = mgr.store(DIM);

        // A cell inside the loaded column (0,0).
        int bx = 3, by = 5 + 16 * 4, bz = 7;            // sectionY = 4, ly = 5
        int sectionY = 4;
        int lx = bx & 15, ly = by & 15, lz = bz & 15;
        int sectionCell = lx + 16 * ly + 256 * lz;

        // Pre-break: the cell holds a real substance (water).
        store.setMaterialAt(0, 0, sectionY, sectionCell, ORGE_WATER);
        assertEquals(ORGE_WATER, store.materialAt(0, 0, sectionY, sectionCell), "precondition: water");

        // ---- The exact in-game break mutation (BlockEvent.BREAK → wakeBreak → captureBreak). ----
        world.captureBreak(DIM, bx, by, bz);

        // ===================================================================================
        // (1) FUNCTIONAL / SIM TRUTH: the stored per-cell material is orge:vacuum (mass 0).
        //     This is what the engine assembler reads as authoritative — a fillable empty cell,
        //     NOT a sub-min air ghost. Fluids CAN flow in. So the functional complaint is fixed.
        // ===================================================================================
        assertEquals(ORGE_VACUUM, store.materialAt(0, 0, sectionY, sectionCell),
                "SIM TRUTH: broken cell is durable orge:vacuum in the store (fillable; fluids can flow in)");

        // ===================================================================================
        // (2) DISPLAY TRUTH: what /orge get actually prints for the material label.
        //     OrgeCommands.liveCellDescriptor reads the LIVE Minecraft block (post-break:
        //     minecraft:air) and maps it via LiveMaterials.materialFor → BlockMaterialRule.firstTouch.
        //     It NEVER consults the stored per-cell material. So the label is orge:air.
        // ===================================================================================
        MaterialRegistry reg = registryWithAirAndWater();
        Identifier displayedMaterial =
                BlockMaterialRule.firstTouch(MC_AIR, reg); // == LiveMaterials.materialFor(minecraft:air block)
        assertEquals(ORGE_AIR, displayedMaterial,
                "DISPLAY: /orge get renders the material from the LIVE minecraft:air block ⇒ orge:air");

        // ===================================================================================
        // THE BUG, made explicit: the displayed material (orge:air, from the block) DISAGREES
        // with the stored/sim material (orge:vacuum). The mass shown (0) is the store's real mass.
        // ⇒ "orge:air @ 0 kg" is a DISPLAY-ONLY artifact: block-derived material + store-derived mass.
        // The cell is genuinely vacuum in the sim; the /orge get label mislabels it from the block.
        // ===================================================================================
        assertNotEquals(displayedMaterial, store.materialAt(0, 0, sectionY, sectionCell),
                "ROOT CAUSE: displayed material (block-derived orge:air) != stored material (orge:vacuum)");
    }

    /**
     * THE FIX (2026-06-19): {@code /orge get} now renders the material from the STORED per-cell
     * species for a simulated cell (via {@link SectionView#material}), not the live block. This test
     * drives the same break, then reads through the exact source the fixed display uses
     * ({@link ServerStoreReadSource} → {@link SectionView#material}) and asserts it reports the
     * sim-true {@code orge:vacuum} on a non-ambient (simulated) cell.
     */
    @Test
    void afterBreak_sectionViewMaterialReportsStoredVacuum_soFixedDisplayShowsVacuum(@TempDir Path dir) {
        SectionStoreManager mgr = loadedManager(dir);
        MinecraftThermalWorld world = new MinecraftThermalWorld(mgr);
        SectionStore store = mgr.store(DIM);

        int bx = 3, by = 5 + 16 * 4, bz = 7;
        int sectionY = 4;
        int sectionCell = (bx & 15) + 16 * (by & 15) + 256 * (bz & 15);
        store.setMaterialAt(0, 0, sectionY, sectionCell, ORGE_WATER);
        world.captureBreak(DIM, bx, by, bz);

        ServerStoreReadSource readSource = new ServerStoreReadSource(mgr);
        Optional<SectionView> v = readSource.section(DIM, new SubchunkKey(0, sectionY, 0));
        assertTrue(v.isPresent(), "store is loaded ⇒ a view exists");
        SectionView view = v.get();
        assertFalse(view.ambient(), "the section is simulated (loaded), so the stored material is authoritative");
        assertEquals(ORGE_VACUUM, view.material(sectionCell),
                "FIX: the display source now reports the stored orge:vacuum, not the block's orge:air");
        assertEquals(0f, view.massAt(sectionCell), 1e-6f, "and the broken cell's stored mass is 0");
    }
}
