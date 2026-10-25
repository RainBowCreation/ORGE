package net.rainbowcreation.orge.scheduler;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.EnthalpyCurve;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialData;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Shared fixture for the sky forcing tests: the REAL bundled material table, and a one-column store
 * whose stored sections hold real materials at a real temperature with E on each material's curve.
 */
final class SkyTestWorld {

    static final Identifier OVERWORLD = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    static final Identifier THE_END = Identifier.fromNamespaceAndPath("minecraft", "the_end");
    static final Identifier THE_NETHER = Identifier.fromNamespaceAndPath("minecraft", "the_nether");

    static final Identifier AIR = orge("air");
    static final Identifier STONE = orge("stone");
    static final Identifier DIRT = orge("dirt");
    static final Identifier WATER = orge("water");
    static final Identifier OAK_LEAVES = orge("oak_leaves");

    private static MaterialRegistry registry;

    private SkyTestWorld() { }

    static Identifier orge(String path) { return Identifier.fromNamespaceAndPath("orge", path); }

    /** Every bundled {@code materials/*.json}, through the real codec (so phase chains resolve). */
    static synchronized MaterialRegistry registry() {
        if (registry != null) return registry;
        try {
            Path dir = Path.of(SkyTestWorld.class.getResource("/data/orge/orge/materials").toURI());
            Map<Identifier, JsonElement> files = new LinkedHashMap<>();
            try (Stream<Path> list = Files.list(dir)) {
                for (Path p : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".json")).sorted()::iterator) {
                    String n = p.getFileName().toString();
                    try (InputStream in = Files.newInputStream(p)) {
                        files.put(orge(n.substring(0, n.length() - 5)),
                                JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
                    }
                }
            }
            MaterialRegistry reg = new MaterialRegistry();
            MaterialData.loadMaterials(files, reg);
            registry = reg;
            return reg;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Function<Identifier, Material> lookup() {
        MaterialRegistry reg = registry();
        return id -> reg.get(id).orElse(null);
    }

    static Material mat(Identifier id) {
        return registry().get(id).orElseThrow();
    }

    /** A store manager for {@code dim} with column (0,0) loaded. */
    static SectionStoreManager loaded(Path dir, Identifier dim) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(dim, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(dim, 0, 0);
        return mgr;
    }

    /** Section cell index of {@code (x, sy, z)}. */
    static int cell(int x, int sy, int z) { return x + 16 * sy + 256 * z; }

    /** Store a section of {@code store} filled by {@code fill(sy)} (material per local y) at {@code tempK}. */
    static SectionData putSection(SectionStore store, int sectionY, Function<Integer, Identifier> fill, float tempK) {
        SectionData d = SectionData.uniform(0f, 0f);
        for (int i = 0; i < SectionData.CELLS; i++) {
            Material m = mat(fill.apply((i >> 4) & 15));
            setCell(d, i, m, m.defaultMass(), tempK);
        }
        store.put(new SubchunkKey(0, sectionY, 0), d);
        return d;
    }

    /** Make cell {@code i} a one-component cell of {@code m} at {@code mass} and {@code tempK} (E on its curve). */
    static void setCell(SectionData d, int i, Material m, float mass, float tempK) {
        d.setMaterialAt(i, m.id());
        d.setMass(i, mass);
        d.setEnthalpy(i, (float) EnthalpyCurve.cellE(mass, m, lookup(), tempK));
    }

    /**
     * The flat test world: sections 2..3 stone, section 4 stone below local y 8 and {@code surface} at y 8
     * (no surface layer when null), air above; section 5 all air. Sections above stay unstored.
     */
    static void flatWorld(SectionStore store, Identifier surface, float tempK) {
        putSection(store, 2, sy -> STONE, tempK);
        putSection(store, 3, sy -> STONE, tempK);
        putSection(store, 4, sy -> sy < 8 ? STONE : (sy == 8 && surface != null ? surface : AIR), tempK);
        putSection(store, 5, sy -> AIR, tempK);
    }

    /** Exact Σ E [J] over every stored section of column (0,0) (float values summed without rounding). */
    static BigDecimal grandE(SectionStore store) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int sy = ColumnAssembler.MIN_SECTION_Y; sy <= ColumnAssembler.MAX_SECTION_Y; sy++) {
            SubchunkKey key = new SubchunkKey(0, sy, 0);
            if (!store.hasSection(key)) continue;
            SectionData d = store.get(key);
            for (int i = 0; i < SectionData.CELLS; i++) sum = sum.add(new BigDecimal(d.enthalpyAt(i)));
        }
        return sum;
    }

    /** Derived T of a one-component cell. */
    static float tempOf(SectionData d, int i) {
        return EnthalpyCurve.deriveT(d.enthalpyAt(i), d.massAt(i), mat(d.materialAt(i)), lookup(), Float.NaN);
    }
}
