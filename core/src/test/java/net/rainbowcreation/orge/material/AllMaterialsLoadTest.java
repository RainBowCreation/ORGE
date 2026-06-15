package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loads EVERY bundled {@code data/orge/orge/materials/*.json} through the real codec
 * ({@link MaterialData#loadMaterials}) under the canonical schema. Guards that the
 * shipped datapack is clean: all parse, every phase target resolves to a real material
 * file, and the headline materials carry their canonical values.
 */
class AllMaterialsLoadTest {

    private static Identifier orge(String path) { return Identifier.fromNamespaceAndPath("orge", path); }

    /** Resolve the materials directory on the classpath and list every {@code *.json} stem. */
    private static Set<String> materialStems() throws Exception {
        URL dir = AllMaterialsLoadTest.class.getResource("/data/orge/orge/materials");
        assertNotNull(dir, "materials directory must be on the classpath");
        Path path = Path.of(dir.toURI());
        Set<String> stems = new TreeSet<>();
        try (Stream<Path> files = Files.list(path)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        String n = p.getFileName().toString();
                        stems.add(n.substring(0, n.length() - ".json".length()));
                    });
        }
        return stems;
    }

    private static JsonElement resource(String stem) {
        String p = "/data/orge/orge/materials/" + stem + ".json";
        try (InputStream in = AllMaterialsLoadTest.class.getResourceAsStream(p)) {
            assertNotNull(in, "missing resource " + p);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MaterialRegistry loadAll() throws Exception {
        Map<Identifier, JsonElement> files = new LinkedHashMap<>();
        for (String stem : materialStems()) {
            files.put(orge(stem), resource(stem));
        }
        MaterialRegistry reg = new MaterialRegistry();
        MaterialData.loadMaterials(files, reg);
        return reg;
    }

    // -------------------------------------------------------------------------
    // (a) every materials/*.json parses without error
    // -------------------------------------------------------------------------

    @Test
    void everyMaterialFileParses() throws Exception {
        MaterialRegistry reg = loadAll();
        for (String stem : materialStems()) {
            assertTrue(reg.get(orge(stem)).isPresent(), "material orge:" + stem + " must load");
        }
    }

    // -------------------------------------------------------------------------
    // (b) every min_target/max_target resolves to an existing material file
    // -------------------------------------------------------------------------

    @Test
    void everyPhaseTargetResolvesToAMaterialFile() throws Exception {
        MaterialRegistry reg = loadAll();
        Set<String> stems = materialStems();
        Set<Identifier> ids = new TreeSet<>(java.util.Comparator.comparing(Identifier::toString));
        for (String stem : stems) ids.add(orge(stem));

        for (String stem : stems) {
            Material m = reg.get(orge(stem)).orElseThrow();
            if (m.minTarget() != null) {
                assertTrue(ids.contains(m.minTarget()),
                        "orge:" + stem + " min_target " + m.minTarget()
                                + " must be an existing material id (a material file in the set)");
            }
            if (m.maxTarget() != null) {
                assertTrue(ids.contains(m.maxTarget()),
                        "orge:" + stem + " max_target " + m.maxTarget()
                                + " must be an existing material id (a material file in the set)");
            }
        }
    }

    // -------------------------------------------------------------------------
    // (c) orge:water targets are material ids
    // -------------------------------------------------------------------------

    @Test
    void waterTargetsAreMaterialIds() throws Exception {
        MaterialRegistry reg = loadAll();
        Material water = reg.get(orge("water")).orElseThrow();
        assertEquals(orge("ice"), water.minTarget(), "water freezes to the orge:ice MATERIAL (not minecraft:ice)");
        assertEquals(orge("steam"), water.maxTarget(), "water boils to the orge:steam MATERIAL");
    }

    // -------------------------------------------------------------------------
    // (d) generic_solid has NO viscosity (frozen)
    // -------------------------------------------------------------------------

    @Test
    void genericSolidIsFrozen() throws Exception {
        MaterialRegistry reg = loadAll();
        Material gs = reg.get(orge("generic_solid")).orElseThrow();
        assertTrue(Float.isInfinite(gs.viscosity()),
                "generic_solid omits viscosity => frozen (+Infinity)");
        assertFalse(gs.movable());
    }

    // -------------------------------------------------------------------------
    // (e) orge:air has a finite viscosity and a low molar_mass
    // -------------------------------------------------------------------------

    @Test
    void airIsMovableLightGas() throws Exception {
        MaterialRegistry reg = loadAll();
        Material air = reg.get(orge("air")).orElseThrow();
        assertTrue(Float.isFinite(air.viscosity()), "air has a finite viscosity (movable gas)");
        assertTrue(air.movable());
        assertTrue(air.molarMass() < 0.05f, "air has a low molar_mass (light gas), got " + air.molarMass());
    }

    // -------------------------------------------------------------------------
    // (f) molar_mass carries the REAL SI molar masses (kg/mol), the input to the
    //     gas EOS (v4 law #8 / §2.1) — NOT a buoyancy sort key.
    //
    //     The old A-unify invariant (air < water < ... molar-sorted buoyancy) is
    //     OBSOLETE: with real SI values air 0.029 > water 0.018 = steam 0.018, so a
    //     molar-mass sort would float water on air, which is wrong. In Engine-B
    //     buoyancy is density/EOS-driven, not molar-mass-sorted; molar_mass only
    //     feeds the gas EOS. Anchor the SI reality (engine_b_real_lut.hpp lines 16-17,
    //     35-47): water/steam/ice 0.018, air 0.029, lava/stone 0.065.
    // -------------------------------------------------------------------------

    @Test
    void molarMassMatchesRealSiValues() throws Exception {
        MaterialRegistry reg = loadAll();
        assertEquals(0.029f, reg.get(orge("air")).orElseThrow().molarMass(),   1e-6f, "air = 0.029 kg/mol (SI)");
        assertEquals(0.018f, reg.get(orge("water")).orElseThrow().molarMass(), 1e-6f, "water = 0.018 kg/mol (SI)");
        assertEquals(0.018f, reg.get(orge("steam")).orElseThrow().molarMass(), 1e-6f, "steam = 0.018 kg/mol (H2O, SI)");
        assertEquals(0.065f, reg.get(orge("lava")).orElseThrow().molarMass(),  1e-6f, "lava = 0.065 kg/mol (SI)");
        assertEquals(0.065f, reg.get(orge("stone")).orElseThrow().molarMass(), 1e-6f, "stone = 0.065 kg/mol (SI)");
        assertEquals(0.018f, reg.get(orge("ice")).orElseThrow().molarMass(),   1e-6f, "ice = 0.018 kg/mol (SI)");
    }

    // -------------------------------------------------------------------------
    // (g) T10b-D drift-guard: the LOADED canonical 6 carry their exact v4 §1.2
    //     thermal fields. Values must equal ORGE-ENGINE/tests/engine_b_real_lut.hpp
    //     so headless == in-game (v4 §1.2). Any future schema/data drift in
    //     emissivity, thermal_expansion, latent heats, T_ref_gas, mass bands, or
    //     phase thresholds/targets fails HERE rather than silently in-game.
    // -------------------------------------------------------------------------

    @Test
    void canonicalMaterialsCarryV4ThermalFields() throws Exception {
        MaterialRegistry reg = loadAll();
        Material water = reg.get(orge("water")).orElseThrow();
        Material lava  = reg.get(orge("lava")).orElseThrow();
        Material air   = reg.get(orge("air")).orElseThrow();
        Material stone = reg.get(orge("stone")).orElseThrow();
        Material steam = reg.get(orge("steam")).orElseThrow();
        Material ice   = reg.get(orge("ice")).orElseThrow();

        // --- emissivity ε (real_lut.hpp: water 0.96, lava 0.95, air 0, stone 0.90, steam 0, ice 0.97)
        assertEquals(0.96f, water.emissivity(), 1e-3f, "water ε");
        assertEquals(0.95f, lava.emissivity(),  1e-3f, "lava ε");
        assertEquals(0.0f,  air.emissivity(),   1e-3f, "air ε");
        assertEquals(0.90f, stone.emissivity(), 1e-3f, "stone ε");
        assertEquals(0.0f,  steam.emissivity(), 1e-3f, "steam ε");
        assertEquals(0.97f, ice.emissivity(),   1e-3f, "ice ε");

        // --- gas ε = 0 invariant (law #6): gases are radiatively transparent
        assertEquals(0f, air.emissivity(),   "air is a gas => ε must be 0 (law #6)");
        assertEquals(0f, steam.emissivity(), "steam is a gas => ε must be 0 (law #6)");

        // --- thermal expansion β [1/K] (real_lut.hpp lines 36-47)
        assertEquals(2.1e-4f, water.thermalExpansion(), 1e-9f, "water β");
        assertEquals(5e-5f,   lava.thermalExpansion(),  1e-9f, "lava β");
        assertEquals(0.0f,    air.thermalExpansion(),   1e-9f, "air β (gas: EOS does expansion)");
        assertEquals(2e-5f,   stone.thermalExpansion(), 1e-9f, "stone β");
        assertEquals(0.0f,    steam.thermalExpansion(), 1e-9f, "steam β (gas)");
        assertEquals(5e-5f,   ice.thermalExpansion(),   1e-9f, "ice β");

        // --- latent heats J/kg (relative tol so the big numbers don't need exact float)
        // water: freeze 3.34e5, boil 2.256e6
        assertEquals(3.34e5f,  water.latentHeatMin(), Math.abs(3.34e5f) * 1e-4f + 1e-6f, "water L_min (freeze)");
        assertEquals(2.256e6f, water.latentHeatMax(), Math.abs(2.256e6f) * 1e-4f + 1e-6f, "water L_max (boil)");
        // lava: solidify 4.0e5, no max transition
        assertEquals(4.0e5f, lava.latentHeatMin(), Math.abs(4.0e5f) * 1e-4f + 1e-6f, "lava L_min (solidify)");
        assertEquals(0f,     lava.latentHeatMax(), 1e-6f, "lava L_max = 0");
        // air: none
        assertEquals(0f, air.latentHeatMin(), 1e-6f, "air L_min = 0");
        assertEquals(0f, air.latentHeatMax(), 1e-6f, "air L_max = 0");
        // stone: melt 4.0e5 on the max side
        assertEquals(0f,     stone.latentHeatMin(), 1e-6f, "stone L_min = 0");
        assertEquals(4.0e5f, stone.latentHeatMax(), Math.abs(4.0e5f) * 1e-4f + 1e-6f, "stone L_max (melt)");
        // steam: condense 2.256e6 on the max side (per loaded datapack)
        assertEquals(0f,       steam.latentHeatMin(), 1e-6f, "steam L_min = 0");
        assertEquals(2.256e6f, steam.latentHeatMax(), Math.abs(2.256e6f) * 1e-4f + 1e-6f, "steam L_max (condense)");
        // ice: melt 3.34e5 on the max side
        assertEquals(0f,      ice.latentHeatMin(), 1e-6f, "ice L_min = 0");
        assertEquals(3.34e5f, ice.latentHeatMax(), Math.abs(3.34e5f) * 1e-4f + 1e-6f, "ice L_max (melt)");

        // --- per-gas EOS reference temperature T_ref_gas (real_lut.hpp lines 60-62)
        assertEquals(288f, air.tRefGas(),   1e-3f, "air T_ref_gas = 288");
        assertEquals(373f, steam.tRefGas(), 1e-3f, "steam T_ref_gas = 373");
        assertEquals(0f,   water.tRefGas(), 1e-3f, "water T_ref_gas = 0 (non-gas default)");
        assertEquals(0f,   lava.tRefGas(),  1e-3f, "lava T_ref_gas = 0 (non-gas default)");
        assertEquals(0f,   stone.tRefGas(), 1e-3f, "stone T_ref_gas = 0 (non-gas default)");
        assertEquals(0f,   ice.tRefGas(),   1e-3f, "ice T_ref_gas = 0 (non-gas default)");

        // --- mass bands minMass / defaultMass / maxMass (real_lut.hpp lines 36-47)
        assertEquals(125f,  water.minMass(),     1e-3f, "water minMass");
        assertEquals(1000f, water.defaultMass(), 1e-3f, "water defaultMass");
        assertEquals(1000f, water.maxMass(),     1e-3f, "water maxMass");
        assertEquals(330f,  lava.minMass(),      1e-3f, "lava minMass");
        assertEquals(2650f, lava.defaultMass(),  1e-3f, "lava defaultMass");
        assertEquals(2650f, lava.maxMass(),      1e-3f, "lava maxMass");
        assertEquals(1.0f,  air.minMass(),       1e-3f, "air minMass");
        assertEquals(1.2f,  air.defaultMass(),   1e-3f, "air defaultMass");
        assertEquals(1000f, air.maxMass(),       1e-3f, "air maxMass");
        assertEquals(2700f, stone.minMass(),     1e-3f, "stone minMass");
        assertEquals(2700f, stone.defaultMass(), 1e-3f, "stone defaultMass");
        assertEquals(2700f, stone.maxMass(),     1e-3f, "stone maxMass");
        assertEquals(0.06f, steam.minMass(),     1e-3f, "steam minMass");
        assertEquals(0.6f,  steam.defaultMass(), 1e-3f, "steam defaultMass");
        assertEquals(1000f, steam.maxMass(),     1e-3f, "steam maxMass");
        assertEquals(917f,  ice.minMass(),       1e-3f, "ice minMass");
        assertEquals(917f,  ice.defaultMass(),   1e-3f, "ice defaultMass");
        assertEquals(917f,  ice.maxMass(),       1e-3f, "ice maxMass");

        // --- phase thresholds (INTEGER, not .15) + targets (real_lut.hpp lines 48-59).
        // "no side" => threshold is ∓∞ and the target is null (Material builder defaults).
        // water: freeze 273 -> ice, boil 373 -> steam
        assertEquals(273f, water.minTemp(), 1e-3f, "water minTemp (integer 273, not 273.15)");
        assertEquals(orge("ice"), water.minTarget(), "water minTarget");
        assertEquals(373f, water.maxTemp(), 1e-3f, "water maxTemp (integer 373)");
        assertEquals(orge("steam"), water.maxTarget(), "water maxTarget");
        // lava: solidify 1275 -> stone; no max side
        assertEquals(1275f, lava.minTemp(), 1e-3f, "lava minTemp");
        assertEquals(orge("stone"), lava.minTarget(), "lava minTarget");
        assertTrue(Float.isInfinite(lava.maxTemp()), "lava has no max transition (maxTemp = +∞)");
        assertNull(lava.maxTarget(), "lava has no maxTarget");
        // stone: melt 1450 -> lava; no min side
        assertEquals(1450f, stone.maxTemp(), 1e-3f, "stone maxTemp");
        assertEquals(orge("lava"), stone.maxTarget(), "stone maxTarget");
        assertTrue(Float.isInfinite(stone.minTemp()), "stone has no min transition (minTemp = -∞)");
        assertNull(stone.minTarget(), "stone has no minTarget");
        // steam: condense 373 -> water; no max side
        assertEquals(373f, steam.minTemp(), 1e-3f, "steam minTemp");
        assertEquals(orge("water"), steam.minTarget(), "steam minTarget");
        assertTrue(Float.isInfinite(steam.maxTemp()), "steam has no max transition (maxTemp = +∞)");
        assertNull(steam.maxTarget(), "steam has no maxTarget");
        // ice: melt 273 -> water; no min side
        assertEquals(273f, ice.maxTemp(), 1e-3f, "ice maxTemp");
        assertEquals(orge("water"), ice.maxTarget(), "ice maxTarget");
        assertTrue(Float.isInfinite(ice.minTemp()), "ice has no min transition (minTemp = -∞)");
        assertNull(ice.minTarget(), "ice has no minTarget");

        // --- movability invariant (law: movable iff finite viscosity)
        assertFalse(stone.movable(), "stone is an immovable solid");
        assertFalse(ice.movable(),   "ice is an immovable solid");
        assertTrue(Float.isInfinite(stone.viscosity()), "stone viscosity = +∞ (frozen)");
        assertTrue(Float.isInfinite(ice.viscosity()),   "ice viscosity = +∞ (frozen)");
        assertTrue(water.movable(), "water is a movable fluid");
        assertTrue(lava.movable(),  "lava is a movable fluid");
        assertTrue(air.movable(),   "air is a movable gas");
        assertTrue(steam.movable(), "steam is a movable gas");
    }
}
