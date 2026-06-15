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
}
