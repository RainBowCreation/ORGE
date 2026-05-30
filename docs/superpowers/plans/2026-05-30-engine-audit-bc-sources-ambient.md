# Engine-audit B+C — Heat Sources & Ambient Init — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the inert thermal core evolve — pinned heat sources create gradients and biome-derived per-cell seeding kills the flat 285 K — so the water-boils-next-to-lava audit works.

**Architecture:** New pure `core` units (Material field, `BiomeTemperature`, `AmbientSeeder`, `SourcePinPlanner`, `BlockStatePredicate`/`PropertyView`, extended `MaterialBindings`) sit behind the existing Minecraft seams (`MinecraftThermalWorld`, `MinecraftPhaseChanger`, `LiveMaterials`). Per second: snapshot → engine.step → writeBack → phase change → re-pin surviving sources. No `liborge`/native change.

**Tech Stack:** Java 21, Architectury multiloader (MC 1.21.11, Mojang mappings; `Identifier` = `ResourceLocation`), Mojang DFU codecs, JUnit 5. Build env: `JAVA_HOME=/home/claude/jdk21`.

**Spec:** `docs/superpowers/specs/2026-05-30-engine-audit-bc-sources-ambient-design.md`

---

## Conventions for every task

- Run one test class: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "<FQCN>" --rerun-tasks`
- Whole core suite: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
- All 3 loaders compile: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
- Commit per task with the message shown. **Push `origin/rebuild` only at the very end** (Task 16). No PR/merge to main.

---

## File structure

**New pure `core` files:**
- `core/src/main/java/net/rainbowcreation/orge/scheduler/BiomeTemperature.java` — biome-base→Kelvin formula.
- `core/src/main/java/net/rainbowcreation/orge/scheduler/AmbientSeeder.java` — per-cell seed array.
- `core/src/main/java/net/rainbowcreation/orge/phase/SourcePinPlanner.java` — conditional re-pin resets.
- `core/src/main/java/net/rainbowcreation/orge/material/PropertyView.java` — loader-agnostic blockstate property accessor.
- `core/src/main/java/net/rainbowcreation/orge/material/BlockStatePredicate.java` — predicate parse + match (`=`, `>`).

**Modified files:**
- `material/Material.java` — +2 fields, compat constructor, `hasDefaultTemperature()`.
- `material/MaterialCodec.java` — parse `default_temperature` + `pinned`, validation.
- `material/MaterialBindings.java` — predicated overrides/tags, `PropertyView`-aware `materialFor`.
- `material/MaterialData.java` — parse `[...]` predicate keys.
- `scheduler/LiveMaterials.java` — `blockStateAt`, `materialFor(BlockState, …)`.
- `scheduler/MinecraftThermalWorld.java` — per-cell seeding + biome sample + blockstate accessor.
- `phase/MinecraftPhaseChanger.java` — re-pin pass + blockstate accessor.
- `command/OrgeCommandLogic.java` — `Locale.ROOT` on all `String.format`.

**New data files:** material JSON under `core/src/main/resources/data/orge/orge/materials/` and binding entries in `core/src/main/resources/data/orge/orge/bindings/default.json`.

**New test files:** one per new/changed pure unit + an audit integration test.

---

## Task 1: Material — `default_temperature` + `pinned` fields

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/Material.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialFieldsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialFieldsTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "t");

    @Test
    void legacyElevenArgConstructorDefaultsNewFields() {
        Material m = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
        assertTrue(Float.isNaN(m.defaultTemperature()));
        assertFalse(m.pinned());
        assertFalse(m.hasDefaultTemperature());
    }

    @Test
    void fullConstructorCarriesNewFields() {
        Material m = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null,
                1400f, true);
        assertEquals(1400f, m.defaultTemperature(), 1e-5f);
        assertTrue(m.pinned());
        assertTrue(m.hasDefaultTemperature());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialFieldsTest" --rerun-tasks`
Expected: FAIL — compile error (`defaultTemperature()`/`pinned()` and 13-arg constructor not defined).

- [ ] **Step 3: Implement**

Replace the record body in `Material.java` (keep the existing javadoc; append two `@param` lines and the new fields + constructor + helper):

```java
public record Material(
        Identifier id,
        float thermalConductivity,
        float heatCapacity,
        float viscosity,
        float defaultMass,
        float molarMass,
        float boilingPoint,
        float freezingPoint,
        Identifier boilingTarget,
        Identifier freezingTarget,
        Identifier representativeBlock,
        float defaultTemperature,
        boolean pinned
) {
    /** Backward-compatible constructor: no natural/pin temperature, not a source. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float boilingPoint, float freezingPoint,
                    Identifier boilingTarget, Identifier freezingTarget,
                    Identifier representativeBlock) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock,
                Float.NaN, false);
    }

    /** True when a natural/seed/pin temperature is defined (i.e. {@code default_temperature} present). */
    public boolean hasDefaultTemperature() {
        return !Float.isNaN(defaultTemperature);
    }
    // TODO(phase: materials): builder + validate non-negative constants.
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialFieldsTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/material/Material.java core/src/test/java/net/rainbowcreation/orge/material/MaterialFieldsTest.java
git commit -m "feat(material): default_temperature + pinned fields (compat ctor)"
```

---

## Task 2: MaterialCodec — parse `default_temperature` + `pinned`

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/MaterialCodec.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialCodecSourceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialCodecSourceTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "src");

    @Test
    void parsesDefaultTemperatureAndPinned() {
        String json = """
                { "thermal_conductivity": 1.5, "heat_capacity": 1450, "default_mass": 3100,
                  "default_temperature": 1400, "pinned": true }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(1400f, m.defaultTemperature(), 1e-5f);
        assertTrue(m.pinned());
    }

    @Test
    void defaultsWhenAbsent() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertFalse(m.hasDefaultTemperature());
        assertFalse(m.pinned());
    }

    @Test
    void pinnedWithoutDefaultTemperatureThrows() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000,
                  "pinned": true }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> MaterialCodec.fromJson(ID, JsonParser.parseString(json)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialCodecSourceTest" --rerun-tasks`
Expected: FAIL — `parsesDefaultTemperatureAndPinned` sees NaN/false (fields not parsed); `pinnedWithoutDefaultTemperatureThrows` does not throw.

- [ ] **Step 3: Implement**

In `MaterialCodec.java`: add two fields to `BodyData` (after `representativeBlock`):

```java
    record BodyData(
            float thermalConductivity,
            float heatCapacity,
            float viscosity,
            float defaultMass,
            float molarMass,
            float boilingPoint,
            float freezingPoint,
            Optional<Identifier> boilingTarget,
            Optional<Identifier> freezingTarget,
            Optional<Identifier> representativeBlock,
            float defaultTemperature,
            boolean pinned
    ) {}
```

Add the two codec fields inside `instance.group(...)` (after the `representative_block` line, before `.apply`):

```java
                    Identifier.CODEC.optionalFieldOf("representative_block")
                            .forGetter(BodyData::representativeBlock),
                    Codec.FLOAT.optionalFieldOf("default_temperature", Float.NaN)
                            .forGetter(BodyData::defaultTemperature),
                    Codec.BOOL.optionalFieldOf("pinned", false)
                            .forGetter(BodyData::pinned)
            ).apply(instance, BodyData::new)
```

In `fromJson`, validate and pass the new fields to the 13-arg constructor:

```java
    public static Material fromJson(Identifier id, JsonElement body) {
        DataResult<BodyData> result = BODY_CODEC.parse(JsonOps.INSTANCE, body);
        BodyData bd = result.getOrThrow(IllegalArgumentException::new);
        if (bd.pinned() && Float.isNaN(bd.defaultTemperature())) {
            throw new IllegalArgumentException(
                    "material " + id + ": pinned=true requires default_temperature");
        }
        return new Material(
                id,
                bd.thermalConductivity(),
                bd.heatCapacity(),
                bd.viscosity(),
                bd.defaultMass(),
                bd.molarMass(),
                bd.boilingPoint(),
                bd.freezingPoint(),
                bd.boilingTarget().orElse(null),
                bd.freezingTarget().orElse(null),
                bd.representativeBlock().orElse(null),
                bd.defaultTemperature(),
                bd.pinned()
        );
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialCodecSourceTest" --rerun-tasks`
Expected: PASS. Also run the existing `MaterialCodecTest` to confirm no regression:
`JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialCodecTest" --rerun-tasks` → PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/material/MaterialCodec.java core/src/test/java/net/rainbowcreation/orge/material/MaterialCodecSourceTest.java
git commit -m "feat(material): codec parses default_temperature + pinned (validated)"
```

---

## Task 3: BiomeTemperature — biome base → Kelvin

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/BiomeTemperature.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/BiomeTemperatureTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeTemperatureTest {
    @Test void temperateAnchorsAt285() { assertEquals(285f, BiomeTemperature.toKelvin(0.8f), 1e-4f); }
    @Test void snowy()  { assertEquals(269f, BiomeTemperature.toKelvin(0.0f), 1e-4f); }
    @Test void desert() { assertEquals(309f, BiomeTemperature.toKelvin(2.0f), 1e-4f); }
    @Test void negativeBiome() { assertEquals(259f, BiomeTemperature.toKelvin(-0.5f), 1e-4f); }
    @Test void clampsNonNegative() { assertTrue(BiomeTemperature.toKelvin(-100f) >= 0f); }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.BiomeTemperatureTest" --rerun-tasks`
Expected: FAIL — `BiomeTemperature` not defined.

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.orge.scheduler;

/**
 * Pure mapping from a Minecraft biome base temperature (snowy≈0.0, temperate≈0.8,
 * desert≈2.0) to Kelvin for seeding never-simulated cells (DESIGN §5, engine-audit B).
 * Anchored so temperate (0.8) yields exactly {@code SectionData.DEFAULT_AMBIENT_K} (285 K).
 */
public final class BiomeTemperature {

    private BiomeTemperature() {}

    /** {@code 285 + (base - 0.8) * 20}, clamped to the engine's [0, 6000] K range. */
    public static float toKelvin(float biomeBase) {
        float k = 285f + (biomeBase - 0.8f) * 20f;
        if (k < 0f) return 0f;
        if (k > 6000f) return 6000f;
        return k;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.BiomeTemperatureTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/BiomeTemperature.java core/src/test/java/net/rainbowcreation/orge/scheduler/BiomeTemperatureTest.java
git commit -m "feat(scheduler): BiomeTemperature.toKelvin (285 anchor)"
```

---

## Task 4: AmbientSeeder — per-cell seed array

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/AmbientSeeder.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/AmbientSeederTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class AmbientSeederTest {

    private static Material src(float t) {
        return new Material(Identifier.fromNamespaceAndPath("orge", "lava"),
                1f, 1f, 0f, 100f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, t, true);
    }
    private static Material bulk() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "air"),
                1f, 1f, 0f, 1f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null);
    }

    @Test
    void sourceCellsGetDefaultTemperatureBulkGetsAmbient() {
        // cell 0 is a source at 1400; all others are bulk -> ambient 290.
        IntFunction<Material> cells = i -> (i == 0) ? src(1400f) : bulk();
        float[] t = AmbientSeeder.seed(cells, 290f);
        assertEquals(SectionData.CELLS, t.length);
        assertEquals(1400f, t[0], 1e-4f);
        assertEquals(290f, t[1], 1e-4f);
        assertEquals(290f, t[SectionData.CELLS - 1], 1e-4f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.AmbientSeederTest" --rerun-tasks`
Expected: FAIL — `AmbientSeeder` not defined.

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.function.IntFunction;

/**
 * Per-cell initial temperatures for a never-simulated section (engine-audit B): a cell
 * whose material defines {@code default_temperature} (a source) seeds there; every other
 * cell seeds at the section's biome ambient. Pure — the per-cell material lookup is injected.
 */
public final class AmbientSeeder {

    private AmbientSeeder() {}

    public static float[] seed(IntFunction<Material> cellMaterial, float biomeAmbientK) {
        float[] t = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            Material m = cellMaterial.apply(i);
            t[i] = m.hasDefaultTemperature() ? m.defaultTemperature() : biomeAmbientK;
        }
        return t;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.AmbientSeederTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/AmbientSeeder.java core/src/test/java/net/rainbowcreation/orge/scheduler/AmbientSeederTest.java
git commit -m "feat(scheduler): AmbientSeeder per-cell seed (source vs ambient)"
```

---

## Task 5: SourcePinPlanner — conditional re-pin resets

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/phase/SourcePinPlanner.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/phase/SourcePinPlannerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class SourcePinPlannerTest {

    private static Material pinned(float t) {
        return new Material(Identifier.fromNamespaceAndPath("orge", "lava"),
                1f, 1f, 0f, 100f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, t, true);
    }
    private static Material plain() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                1f, 1f, 0f, 100f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null);
    }

    @Test
    void pinnedNotTransitionedIsReset() {
        IntFunction<Material> cells = i -> (i == 5) ? pinned(1400f) : plain();
        List<SourcePinPlanner.Reset> r = SourcePinPlanner.plan(cells, List.of());
        assertEquals(1, r.size());
        assertEquals(5, r.get(0).cellIndex());
        assertEquals(1400f, r.get(0).temperatureK(), 1e-4f);
    }

    @Test
    void pinnedButTransitionedIsSkipped() {
        IntFunction<Material> cells = i -> (i == 5) ? pinned(1400f) : plain();
        List<SourcePinPlanner.Reset> r = SourcePinPlanner.plan(
                cells, List.of(new PhasePlanner.Transition(5,
                        Identifier.fromNamespaceAndPath("minecraft", "stone"))));
        assertTrue(r.isEmpty());
    }

    @Test
    void nonPinnedNeverReset() {
        IntFunction<Material> cells = i -> plain();
        assertTrue(SourcePinPlanner.plan(cells, List.of()).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.phase.SourcePinPlannerTest" --rerun-tasks`
Expected: FAIL — `SourcePinPlanner` not defined.

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.orge.phase;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The conditional re-pin pass (engine-audit C): after the §7 phase plan is computed,
 * every cell that is a {@code pinned} material AND did <em>not</em> transition this step
 * is reset to its {@code default_temperature}. A source overwhelmed past its own threshold
 * transitions (it appears in {@code transitions}) and is therefore left alone — the pin is a
 * restoring force, not a lock. Pure — the per-cell material lookup is injected; callers pass
 * the pre-swap block materials so a surviving source still reads as its source material.
 */
public final class SourcePinPlanner {

    /** Reset cell {@code cellIndex} to {@code temperatureK} (its material's default_temperature). */
    public record Reset(int cellIndex, float temperatureK) {}

    private SourcePinPlanner() {}

    public static List<Reset> plan(IntFunction<Material> cellMaterial,
                                   List<PhasePlanner.Transition> transitions) {
        boolean[] transitioned = new boolean[SectionData.CELLS];
        for (PhasePlanner.Transition t : transitions) {
            transitioned[t.cellIndex()] = true;
        }
        List<Reset> out = new ArrayList<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            if (transitioned[i]) {
                continue;
            }
            Material m = cellMaterial.apply(i);
            if (m.pinned() && m.hasDefaultTemperature()) {
                out.add(new Reset(i, m.defaultTemperature()));
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.phase.SourcePinPlannerTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/phase/SourcePinPlanner.java core/src/test/java/net/rainbowcreation/orge/phase/SourcePinPlannerTest.java
git commit -m "feat(phase): SourcePinPlanner conditional re-pin resets"
```

---

## Task 6: PropertyView + BlockStatePredicate

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/material/PropertyView.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/material/BlockStatePredicate.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/BlockStatePredicateTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BlockStatePredicateTest {

    private static PropertyView view(Map<String, String> m) { return m::get; }

    @Test
    void parsesPlainIdNoRequirements() {
        BlockStatePredicate.Parsed p = BlockStatePredicate.parseKey("minecraft:campfire");
        assertEquals("minecraft:campfire", p.id());
        assertTrue(p.requirements().isEmpty());
    }

    @Test
    void parsesEqualityPredicate() {
        BlockStatePredicate.Parsed p = BlockStatePredicate.parseKey("minecraft:campfire[lit=true]");
        assertEquals("minecraft:campfire", p.id());
        assertEquals(1, p.requirements().size());
        assertTrue(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("lit", "true"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("lit", "false"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of()))); // absent -> no match
    }

    @Test
    void parsesGreaterThanForIntegerPower() {
        BlockStatePredicate.Parsed p = BlockStatePredicate.parseKey("minecraft:redstone_wire[power>0]");
        assertTrue(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("power", "1"))));
        assertTrue(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("power", "15"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("power", "0"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("power", "x")))); // non-numeric
    }

    @Test
    void parsesMultipleRequirements() {
        BlockStatePredicate.Parsed p = BlockStatePredicate.parseKey("a:b[lit=true,power>3]");
        assertEquals(2, p.requirements().size());
        assertTrue(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("lit", "true", "power", "4"))));
        assertFalse(BlockStatePredicate.matchesAll(p.requirements(), view(Map.of("lit", "true", "power", "3"))));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.BlockStatePredicateTest" --rerun-tasks`
Expected: FAIL — types not defined.

- [ ] **Step 3: Implement**

`PropertyView.java`:

```java
package net.rainbowcreation.orge.material;

/**
 * Loader-agnostic read access to a block's blockstate property values, keyed by the
 * serialized property name. Returns the serialized value (e.g. {@code "true"}, {@code "7"})
 * or {@code null} if the block has no such property. Lets {@link MaterialBindings} match
 * blockstate predicates without importing Minecraft; the live seam adapts a {@code BlockState}.
 */
@FunctionalInterface
public interface PropertyView {
    String get(String property);

    /** A view with no properties — every lookup returns null. */
    PropertyView EMPTY = property -> null;
}
```

`BlockStatePredicate.java`:

```java
package net.rainbowcreation.orge.material;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and matches blockstate-predicate binding keys of the form
 * {@code <id>[<prop><op><val>,...]} (engine-audit, §4). Two operators: {@code =} (string
 * equality) and {@code >} (numeric greater-than, for integer properties like {@code power}).
 * Pure and Minecraft-free; property values are read through a {@link PropertyView}.
 */
public final class BlockStatePredicate {

    public enum Op { EQ, GT }

    /** One blockstate requirement, e.g. {@code lit=true} or {@code power>0}. */
    public record Requirement(String property, Op op, String value) {
        public boolean matches(PropertyView props) {
            String actual = props.get(property);
            if (actual == null) {
                return false;
            }
            return switch (op) {
                case EQ -> actual.equals(value);
                case GT -> {
                    Integer a = parseInt(actual);
                    Integer b = parseInt(value);
                    yield a != null && b != null && a > b;
                }
            };
        }
    }

    /** A parsed key: the bare block/tag id plus its (possibly empty) requirement list. */
    public record Parsed(String id, List<Requirement> requirements) {}

    private BlockStatePredicate() {}

    /**
     * Splits {@code "id[a=b,c>d]"} into the id and its requirements. A key with no
     * {@code [...]} yields an empty requirement list (plain, predicate-free binding).
     *
     * @throws IllegalArgumentException on a malformed predicate (missing {@code ]}, empty
     *                                  clause, or no recognised operator in a clause)
     */
    public static Parsed parseKey(String key) {
        int open = key.indexOf('[');
        if (open < 0) {
            return new Parsed(key, List.of());
        }
        if (!key.endsWith("]")) {
            throw new IllegalArgumentException("predicate key missing ']': " + key);
        }
        String id = key.substring(0, open);
        String body = key.substring(open + 1, key.length() - 1);
        List<Requirement> reqs = new ArrayList<>();
        for (String clause : body.split(",")) {
            String c = clause.trim();
            if (c.isEmpty()) {
                throw new IllegalArgumentException("empty predicate clause in: " + key);
            }
            int gt = c.indexOf('>');
            int eq = c.indexOf('=');
            if (gt >= 0) {
                reqs.add(new Requirement(c.substring(0, gt).trim(), Op.GT, c.substring(gt + 1).trim()));
            } else if (eq >= 0) {
                reqs.add(new Requirement(c.substring(0, eq).trim(), Op.EQ, c.substring(eq + 1).trim()));
            } else {
                throw new IllegalArgumentException("predicate clause has no operator: " + c);
            }
        }
        return new Parsed(id, reqs);
    }

    /** True iff every requirement matches {@code props}. Empty list ⇒ always true. */
    public static boolean matchesAll(List<Requirement> requirements, PropertyView props) {
        for (Requirement r : requirements) {
            if (!r.matches(props)) {
                return false;
            }
        }
        return true;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.BlockStatePredicateTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/material/PropertyView.java core/src/main/java/net/rainbowcreation/orge/material/BlockStatePredicate.java core/src/test/java/net/rainbowcreation/orge/material/BlockStatePredicateTest.java
git commit -m "feat(material): PropertyView + BlockStatePredicate (= and > ops)"
```

---

## Task 7: MaterialBindings — predicated overrides/tags + PropertyView-aware resolution

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/MaterialBindings.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialBindingsPredicateTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MaterialBindingsPredicateTest {

    private static final Identifier CAMPFIRE = Identifier.fromNamespaceAndPath("minecraft", "campfire");
    private static final Identifier ORGE_CAMPFIRE = Identifier.fromNamespaceAndPath("orge", "campfire");
    private static final Identifier WIRE = Identifier.fromNamespaceAndPath("minecraft", "redstone_wire");
    private static final Identifier ORGE_PWR = Identifier.fromNamespaceAndPath("orge", "powered_redstone");

    private static final MaterialBindings.TagMembership NO_TAGS = (t, b) -> false;
    private static PropertyView view(Map<String, String> m) { return m::get; }

    @Test
    void predicatedOverrideMatchesOnlyWhenPredicateHolds() {
        MaterialBindings b = new MaterialBindings();
        b.addOverride(CAMPFIRE,
                List.of(new BlockStatePredicate.Requirement("lit", BlockStatePredicate.Op.EQ, "true")),
                ORGE_CAMPFIRE);
        assertEquals(ORGE_CAMPFIRE, b.materialFor(CAMPFIRE, view(Map.of("lit", "true")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID, b.materialFor(CAMPFIRE, view(Map.of("lit", "false")), NO_TAGS));
    }

    @Test
    void greaterThanOverrideForPower() {
        MaterialBindings b = new MaterialBindings();
        b.addOverride(WIRE,
                List.of(new BlockStatePredicate.Requirement("power", BlockStatePredicate.Op.GT, "0")),
                ORGE_PWR);
        assertEquals(ORGE_PWR, b.materialFor(WIRE, view(Map.of("power", "9")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID, b.materialFor(WIRE, view(Map.of("power", "0")), NO_TAGS));
    }

    @Test
    void plainOverloadStillWorksAndDelegatesToEmptyView() {
        MaterialBindings b = new MaterialBindings();
        Identifier water = Identifier.fromNamespaceAndPath("minecraft", "water");
        Identifier orgeWater = Identifier.fromNamespaceAndPath("orge", "water");
        b.addOverride(water, orgeWater);
        assertEquals(orgeWater, b.materialFor(water, NO_TAGS));
        assertEquals(orgeWater, b.materialFor(water, PropertyView.EMPTY, NO_TAGS));
    }

    @Test
    void predicatedTagBinding() {
        MaterialBindings b = new MaterialBindings();
        Identifier candles = Identifier.fromNamespaceAndPath("minecraft", "candles");
        Identifier whiteCandle = Identifier.fromNamespaceAndPath("minecraft", "white_candle");
        b.addTagBinding(candles,
                List.of(new BlockStatePredicate.Requirement("lit", BlockStatePredicate.Op.EQ, "true")),
                ORGE_CAMPFIRE);
        MaterialBindings.TagMembership inCandles = (t, blk) -> t.equals(candles);
        assertEquals(ORGE_CAMPFIRE, b.materialFor(whiteCandle, view(Map.of("lit", "true")), inCandles));
        assertEquals(MaterialRegistry.FALLBACK_ID, b.materialFor(whiteCandle, view(Map.of("lit", "false")), inCandles));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialBindingsPredicateTest" --rerun-tasks`
Expected: FAIL — new overloads/`materialFor(…, PropertyView, …)` not defined.

- [ ] **Step 3: Implement**

Replace the body of `MaterialBindings.java` from the field declarations down (keep package + class javadoc + the `TagMembership` interface). New version:

```java
    /** Per-block plain overrides: blockId → materialId. */
    private final Map<Identifier, Identifier> overrides = new HashMap<>();

    /** Per-block predicated overrides, in registration order; first all-match wins. */
    private final Map<Identifier, List<PredicatedEntry>> predicatedOverrides = new HashMap<>();

    /** Tag bindings (plain and predicated) in registration order. */
    private final List<TagEntry> tagEntries = new ArrayList<>();

    private record PredicatedEntry(List<BlockStatePredicate.Requirement> requirements, Identifier materialId) {}
    private record TagEntry(Identifier tagId, List<BlockStatePredicate.Requirement> requirements, Identifier materialId) {}

    // ------------------------------------------------------------------ mutation

    /** Registers an exact, predicate-free per-block binding. */
    public void addOverride(Identifier blockId, Identifier materialId) {
        overrides.put(blockId, materialId);
    }

    /** Registers a predicated per-block binding (checked before the plain override). */
    public void addOverride(Identifier blockId, List<BlockStatePredicate.Requirement> requirements,
                            Identifier materialId) {
        if (requirements.isEmpty()) {
            overrides.put(blockId, materialId);
            return;
        }
        predicatedOverrides.computeIfAbsent(blockId, k -> new ArrayList<>())
                .add(new PredicatedEntry(List.copyOf(requirements), materialId));
    }

    /** Registers a predicate-free tag binding (first-registered matching tag wins). */
    public void addTagBinding(Identifier tagId, Identifier materialId) {
        tagEntries.add(new TagEntry(tagId, List.of(), materialId));
    }

    /** Registers a predicated tag binding (tag must contain the block AND the predicate hold). */
    public void addTagBinding(Identifier tagId, List<BlockStatePredicate.Requirement> requirements,
                              Identifier materialId) {
        tagEntries.add(new TagEntry(tagId, List.copyOf(requirements), materialId));
    }

    public void clear() {
        overrides.clear();
        predicatedOverrides.clear();
        tagEntries.clear();
    }

    // ------------------------------------------------------------------ resolution

    /** Predicate-free convenience: resolves with no blockstate properties available. */
    public Identifier materialFor(Identifier blockId, TagMembership tags) {
        return materialFor(blockId, PropertyView.EMPTY, tags);
    }

    /**
     * Resolves the material id for {@code blockId}. Order: predicated overrides (first
     * all-match) → plain override → tag entries in order (predicate must also hold) → fallback.
     */
    public Identifier materialFor(Identifier blockId, PropertyView props, TagMembership tags) {
        // 1. predicated overrides
        List<PredicatedEntry> pred = predicatedOverrides.get(blockId);
        if (pred != null) {
            for (PredicatedEntry e : pred) {
                if (BlockStatePredicate.matchesAll(e.requirements(), props)) {
                    return e.materialId();
                }
            }
        }
        // 2. plain override
        Identifier override = overrides.get(blockId);
        if (override != null) {
            return override;
        }
        // 3. tag entries in registration order
        for (TagEntry e : tagEntries) {
            if (tags.contains(e.tagId(), blockId) && BlockStatePredicate.matchesAll(e.requirements(), props)) {
                return e.materialId();
            }
        }
        // 4. fallback
        return MaterialRegistry.FALLBACK_ID;
    }
```

Update the imports at the top of the file to:

```java
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
```

(Remove the now-unused `LinkedHashMap` import.)

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialBindingsPredicateTest" --rerun-tasks`
Expected: PASS. Also run any existing `MaterialBindingsTest` to confirm no regression (plain override/tag order behavior preserved):
`JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialBindingsTest" --rerun-tasks` → PASS (skip if the class does not exist).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/material/MaterialBindings.java core/src/test/java/net/rainbowcreation/orge/material/MaterialBindingsPredicateTest.java
git commit -m "feat(material): blockstate-predicate overrides + tag bindings"
```

---

## Task 8: MaterialData.loadBindings — parse `[...]` predicate keys

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/MaterialData.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialDataPredicateTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MaterialDataPredicateTest {

    private static final MaterialBindings.TagMembership NO_TAGS = (t, b) -> false;
    private static PropertyView view(Map<String, String> m) { return m::get; }

    @Test
    void overrideKeyWithPredicateResolvesConditionally() {
        String json = """
                { "overrides": { "minecraft:campfire[lit=true]": "orge:campfire" } }
                """;
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(JsonParser.parseString(json)), b);
        Identifier campfire = Identifier.fromNamespaceAndPath("minecraft", "campfire");
        assertEquals(Identifier.fromNamespaceAndPath("orge", "campfire"),
                b.materialFor(campfire, view(Map.of("lit", "true")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID,
                b.materialFor(campfire, view(Map.of("lit", "false")), NO_TAGS));
    }

    @Test
    void plainOverrideKeyStillWorks() {
        String json = """
                { "overrides": { "minecraft:water": "orge:water" } }
                """;
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(JsonParser.parseString(json)), b);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "water"),
                b.materialFor(Identifier.fromNamespaceAndPath("minecraft", "water"), NO_TAGS));
    }

    @Test
    void tagKeyWithPredicate() {
        String json = """
                { "tags": [ { "tag": "minecraft:candles[lit=true]", "material": "orge:candle" } ] }
                """;
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(JsonParser.parseString(json)), b);
        Identifier candles = Identifier.fromNamespaceAndPath("minecraft", "candles");
        Identifier whiteCandle = Identifier.fromNamespaceAndPath("minecraft", "white_candle");
        MaterialBindings.TagMembership inCandles = (t, blk) -> t.equals(candles);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "candle"),
                b.materialFor(whiteCandle, view(Map.of("lit", "true")), inCandles));
        assertEquals(MaterialRegistry.FALLBACK_ID,
                b.materialFor(whiteCandle, view(Map.of("lit", "false")), inCandles));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialDataPredicateTest" --rerun-tasks`
Expected: FAIL — `Identifier.parse("minecraft:campfire[lit=true]")` throws (predicate not stripped).

- [ ] **Step 3: Implement**

In `MaterialData.loadBindings`, replace the **tag** parse block and the **override** parse block so each key is split via `BlockStatePredicate.parseKey` before `Identifier.parse`.

Tag block (replace lines that read `tagStr`/`materialStr` and call `addTagBinding`):

```java
                    String tagStr      = obj.get("tag").getAsString();
                    String materialStr = obj.get("material").getAsString();
                    try {
                        BlockStatePredicate.Parsed parsed = BlockStatePredicate.parseKey(tagStr);
                        Identifier tagId      = Identifier.parse(parsed.id());
                        Identifier materialId = Identifier.parse(materialStr);
                        into.addTagBinding(tagId, parsed.requirements(), materialId);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "invalid material binding: tag=\"" + tagStr
                                + "\" material=\"" + materialStr + "\"", e);
                    }
```

Override block (replace the body of the `for` over `overrides.entrySet()`):

```java
                    String blockStr    = entry.getKey();
                    String materialStr = entry.getValue().getAsString();
                    try {
                        BlockStatePredicate.Parsed parsed = BlockStatePredicate.parseKey(blockStr);
                        Identifier blockId    = Identifier.parse(parsed.id());
                        Identifier materialId = Identifier.parse(materialStr);
                        into.addOverride(blockId, parsed.requirements(), materialId);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "invalid material binding: block=\"" + blockStr
                                + "\" material=\"" + materialStr + "\"", e);
                    }
```

(`into.addTagBinding(tagId, parsed.requirements(), materialId)` and `into.addOverride(blockId, parsed.requirements(), materialId)` dispatch to the plain methods when `requirements()` is empty — see Task 7.)

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialDataPredicateTest" --rerun-tasks`
Expected: PASS. Run any existing `MaterialDataTest` → PASS (skip if absent).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/material/MaterialData.java core/src/test/java/net/rainbowcreation/orge/material/MaterialDataPredicateTest.java
git commit -m "feat(material): loadBindings parses [prop=val] predicate keys"
```

---

## Task 9: LiveMaterials — BlockState-aware material lookup [seam]

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/LiveMaterials.java`

This seam touches Minecraft `BlockState`/`Property` and cannot be unit-tested headlessly; it is verified by loader compilation and by the audit/integration flow. It only adapts the already-tested pure logic.

- [ ] **Step 1: Add `blockStateAt` and a `BlockState`-based `materialFor`**

Add these imports:

```java
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.rainbowcreation.orge.material.PropertyView;
```

Add these methods to `LiveMaterials` (keep the existing `materialFor(Block, …)` and `blockAt` for compatibility):

```java
    /** The {@link BlockState} at section-local cell index i (x-fastest, x+16y+256z). */
    public static BlockState blockStateAt(LevelChunkSection section, int i) {
        int x = i & 15;
        int y = (i >> 4) & 15;
        int z = (i >> 8) & 15;
        return section.getBlockState(x, y, z);
    }

    /** The {@link Material} bound to {@code state}, honouring blockstate-predicate bindings. */
    public static Material materialFor(BlockState state, ActiveMaterials.State mats) {
        Block block = state.getBlock();
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        PropertyView props = name -> propertyValue(state, name);
        Identifier matId = mats.bindings().materialFor(blockId, props, LIVE_TAGS);
        return mats.registry().getOrFallback(matId);
    }

    /** Serialized value of property {@code name} on {@code state}, or null if the block lacks it. */
    private static String propertyValue(BlockState state, String name) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        return property == null ? null : nameOf(state, property);
    }

    private static <T extends Comparable<T>> String nameOf(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
```

- [ ] **Step 2: Verify all three loaders compile**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/LiveMaterials.java
git commit -m "feat(scheduler): LiveMaterials blockState-aware materialFor + blockStateAt"
```

---

## Task 10: MinecraftThermalWorld — per-cell seeding + biome sample [seam]

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`

Seam task — verified by loader compilation; the seeding logic itself is the tested `AmbientSeeder`/`BiomeTemperature`. Replaces the flat-285 `ambientTemps()` path with per-cell seeding for never-simulated sections, and switches the per-cell material accessor to `blockStateAt` (so predicate bindings drive geometry + seed).

- [ ] **Step 1: Add the BlockPos import**

```java
import net.minecraft.core.BlockPos;
```

- [ ] **Step 2: Replace the main-section temps build in `snapshot`**

Replace this block (inside the `for (SubchunkKey key : union)` loop):

```java
                GeometryAssembler.Geometry geo = GeometryAssembler.assemble(
                        i -> LiveMaterials.materialFor(LiveMaterials.blockAt(section, i), mats), lut);
                SectionStore store = stores.store(dim);
                float[] temps = (store != null)
                        ? store.get(key).temperatureArray().clone()
                        : ambientTemps();
```

with:

```java
                final LevelChunkSection sec = section;
                java.util.function.IntFunction<net.rainbowcreation.orge.material.Material> cellMat =
                        i -> LiveMaterials.materialFor(LiveMaterials.blockStateAt(sec, i), mats);
                GeometryAssembler.Geometry geo = GeometryAssembler.assemble(cellMat, lut);
                SectionStore store = stores.store(dim);
                float[] temps = sectionTemps(level, store, key, cellMat);
```

- [ ] **Step 3: Replace the neighbour temps build in `neighbor`**

Replace:

```java
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        SectionStore store = stores.store(dim);
        float[] temps = (store != null) ? store.get(key).temperatureArray().clone() : ambientTemps();
        // TODO(perf, §8 follow-on): assembles a full 4096-cell geometry per neighbour but only one 256-cell face is used by the halo. A GeometryAssembler.assembleFace(cells, lut, face) variant would cut this 16x.
        GeometryAssembler.Geometry geo =
                GeometryAssembler.assemble(i -> LiveMaterials.materialFor(LiveMaterials.blockAt(section, i), mats), lut);
        return new HaloAssembler.Neighbor(temps, geo.matIx());
```

with:

```java
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        SectionStore store = stores.store(dim);
        final LevelChunkSection sec = section;
        java.util.function.IntFunction<net.rainbowcreation.orge.material.Material> cellMat =
                i -> LiveMaterials.materialFor(LiveMaterials.blockStateAt(sec, i), mats);
        float[] temps = sectionTemps(level, store, key, cellMat);
        // TODO(perf, §8 follow-on): assembles a full 4096-cell geometry per neighbour but only one 256-cell face is used by the halo. A GeometryAssembler.assembleFace(cells, lut, face) variant would cut this 16x.
        GeometryAssembler.Geometry geo = GeometryAssembler.assemble(cellMat, lut);
        return new HaloAssembler.Neighbor(temps, geo.matIx());
```

- [ ] **Step 4: Replace `ambientTemps()` with the seeding helpers**

Replace the whole `ambientTemps()` method:

```java
    private static float[] ambientTemps() {
        float[] t = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, SectionData.DEFAULT_AMBIENT_K);
        return t;
    }
```

with:

```java
    /**
     * Temperatures for one section: the stored gradient if the section has been simulated,
     * otherwise a per-cell seed (sources at their default_temperature, bulk at biome ambient).
     * Never-simulated sections are seeded but NOT persisted here — the post-step write-back
     * creates the section; if the step is dropped, next second re-seeds (idempotent).
     */
    private float[] sectionTemps(ServerLevel level, SectionStore store, SubchunkKey key,
                                 java.util.function.IntFunction<net.rainbowcreation.orge.material.Material> cellMat) {
        if (store != null && store.hasSection(key)) {
            return store.get(key).temperatureArray().clone();
        }
        return AmbientSeeder.seed(cellMat, biomeAmbientK(level, key));
    }

    /** Biome base temperature sampled once at the section centre, mapped to Kelvin. */
    private static float biomeAmbientK(ServerLevel level, SubchunkKey key) {
        int bx = (key.cx() << 4) + 8;
        int by = (key.sectionY() << 4) + 8;
        int bz = (key.cz() << 4) + 8;
        float base = level.getBiome(new BlockPos(bx, by, bz)).value().getBaseTemperature();
        return BiomeTemperature.toKelvin(base);
    }
```

- [ ] **Step 5: Verify all three loaders compile**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: BUILD SUCCESSFUL. (If `level.getBiome(BlockPos)` resolves to a `Holder<Biome>` whose `.value().getBaseTemperature()` is unavailable under these mappings, use `level.getBiome(pos).value().getBaseTemperature()`'s mapped equivalent — confirm the symbol via `BuiltInRegistries`/`Biome` in the dev jar before adjusting.)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java
git commit -m "feat(scheduler): per-cell biome seeding for never-simulated sections"
```

---

## Task 11: MinecraftPhaseChanger — conditional re-pin pass [seam]

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/phase/MinecraftPhaseChanger.java`

Seam task — verified by loader compilation; the re-pin decision is the tested `SourcePinPlanner`. Adds the re-pin pass after block swaps, removes the early-return-on-empty-plan (sources must re-pin even when nothing transitioned), and switches the material accessor to `blockStateAt`.

- [ ] **Step 1: Add imports**

```java
import net.rainbowcreation.orge.material.Material;
import java.util.function.IntFunction;
```

- [ ] **Step 2: Replace the body from the plan computation to the end of `applyPhaseChanges`**

Replace this section:

```java
        ActiveMaterials.State mats = ActiveMaterials.current();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(
                temps,
                i -> LiveMaterials.materialFor(LiveMaterials.blockAt(section, i), mats),
                BuiltInRegistries.BLOCK::containsKey);
        if (plan.isEmpty()) {
            return;
        }

        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;
        for (PhasePlanner.Transition t : plan) {
            int i = t.cellIndex();
            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockState state = BuiltInRegistries.BLOCK.getValue(t.blockId()).defaultBlockState();
            // UPDATE_CLIENTS only: sync the change to clients but skip the neighbour/physics
            // cascade (DESIGN §7). The chunk light engine still re-lights on the state change;
            // if a light-emitting transition (e.g. lava→stone) ever looks stale, revisit the flag.
            level.setBlock(new BlockPos(ox + x, oy + y, oz + z), state, Block.UPDATE_CLIENTS);
        }
    }
```

with:

```java
        ActiveMaterials.State mats = ActiveMaterials.current();
        final LevelChunkSection sec = section;
        IntFunction<Material> cellMat = i -> LiveMaterials.materialFor(LiveMaterials.blockStateAt(sec, i), mats);

        // Materials are read from PRE-SWAP blocks; the re-pin set is computed before swapping
        // so a surviving source still reads as its source material.
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(
                temps, cellMat, BuiltInRegistries.BLOCK::containsKey);
        List<SourcePinPlanner.Reset> resets = SourcePinPlanner.plan(cellMat, plan);

        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;
        for (PhasePlanner.Transition t : plan) {
            int i = t.cellIndex();
            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockState state = BuiltInRegistries.BLOCK.getValue(t.blockId()).defaultBlockState();
            // UPDATE_CLIENTS only: sync the change to clients but skip the neighbour/physics
            // cascade (DESIGN §7). The chunk light engine still re-lights on the state change;
            // if a light-emitting transition (e.g. lava→stone) ever looks stale, revisit the flag.
            level.setBlock(new BlockPos(ox + x, oy + y, oz + z), state, Block.UPDATE_CLIENTS);
        }

        // Conditional re-pin: hold surviving source cells at their default_temperature (engine-audit C).
        if (!resets.isEmpty()) {
            for (SourcePinPlanner.Reset r : resets) {
                data.setTemperature(r.cellIndex(), r.temperatureK());
            }
            store.put(key, data);
        }
    }
```

- [ ] **Step 3: Verify all three loaders compile**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full core suite (no regressions in scheduler/phase tests)**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: BUILD SUCCESSFUL (all green).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/phase/MinecraftPhaseChanger.java
git commit -m "feat(phase): conditional re-pin of surviving source cells"
```

---

## Task 12: Source data — hot sources (block-id, blockstate, powered redstone)

**Files:**
- Modify: `core/src/main/resources/data/orge/orge/materials/lava.json`
- Create: hot source material JSONs (see table)
- Modify: `core/src/main/resources/data/orge/orge/bindings/default.json`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/HotSourceBindingsTest.java`

> All decorative source materials use this template (only `default_temperature` varies). Thermal
> constants are intentionally modest and **tunable**; they do not affect the lava↔water audit.
> ```json
> { "thermal_conductivity": 1.0, "heat_capacity": 1000, "default_mass": 100,
>   "default_temperature": <T>, "pinned": true }
> ```

- [ ] **Step 1: Write the failing test** (loads the real datapack JSON off the classpath and asserts representative bindings resolve to a pinned material at the right temperature)

```java
package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HotSourceBindingsTest {

    private static JsonElement resource(String path) {
        try (InputStream in = HotSourceBindingsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static PropertyView view(Map<String, String> m) { return m::get; }
    private static final MaterialBindings.TagMembership NO_TAGS = (t, b) -> false;

    private static Material material(String id) {
        JsonElement body = resource("/data/orge/orge/materials/" + id + ".json");
        return MaterialCodec.fromJson(Identifier.fromNamespaceAndPath("orge", id), body);
    }
    private static MaterialBindings bindings() {
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(resource("/data/orge/orge/bindings/default.json")), b);
        return b;
    }

    @Test
    void lavaIsPinnedAt1400() {
        Material lava = material("lava");
        assertTrue(lava.pinned());
        assertEquals(1400f, lava.defaultTemperature(), 1e-3f);
    }

    @Test
    void fireBlockIdBinds() {
        Identifier fire = Identifier.fromNamespaceAndPath("minecraft", "fire");
        assertEquals(Identifier.fromNamespaceAndPath("orge", "fire"),
                bindings().materialFor(fire, PropertyView.EMPTY, NO_TAGS));
        assertTrue(material("fire").pinned());
    }

    @Test
    void litCampfireBindsButUnlitDoesNot() {
        Identifier campfire = Identifier.fromNamespaceAndPath("minecraft", "campfire");
        MaterialBindings b = bindings();
        assertEquals(Identifier.fromNamespaceAndPath("orge", "campfire"),
                b.materialFor(campfire, view(Map.of("lit", "true")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID,
                b.materialFor(campfire, view(Map.of("lit", "false")), NO_TAGS));
    }

    @Test
    void poweredRedstoneWireBindsViaGreaterThan() {
        Identifier wire = Identifier.fromNamespaceAndPath("minecraft", "redstone_wire");
        MaterialBindings b = bindings();
        assertEquals(Identifier.fromNamespaceAndPath("orge", "powered_redstone"),
                b.materialFor(wire, view(Map.of("power", "5")), NO_TAGS));
        assertEquals(MaterialRegistry.FALLBACK_ID,
                b.materialFor(wire, view(Map.of("power", "0")), NO_TAGS));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.HotSourceBindingsTest" --rerun-tasks`
Expected: FAIL — lava not pinned; `orge:fire`/`orge:campfire`/`orge:powered_redstone` materials & bindings absent.

- [ ] **Step 3: Edit `lava.json`** — add the two source fields (keep existing fields):

```json
{
  "thermal_conductivity": 1.5,
  "heat_capacity": 1450,
  "default_mass": 3100,
  "molar_mass": 0.060,
  "freezing_point": 1000,
  "freezing_target": "minecraft:stone",
  "representative_block": "minecraft:lava",
  "default_temperature": 1400,
  "pinned": true
}
```

- [ ] **Step 4: Create the hot source material files** (one file each, using the template; `<id>.json` → `default_temperature`):

| file | `default_temperature` |
|---|---|
| `fire.json` | 1100 |
| `campfire.json` | 1000 |
| `furnace_lit.json` | 900 |
| `portal.json` | 800 |
| `torch.json` | 800 |
| `lantern.json` | 700 |
| `magma.json` | 600 |
| `redstone_torch.json` | 600 |
| `candle.json` | 600 |
| `glowstone.json` | 500 |
| `redstone_lamp.json` | 500 |
| `copper_bulb.json` | 500 |
| `redstone_block.json` | 400 |
| `powered_redstone.json` | 400 |
| `lightning_rod.json` | 1500 |

Example (`fire.json`):

```json
{ "thermal_conductivity": 1.0, "heat_capacity": 1000, "default_mass": 100,
  "default_temperature": 1100, "pinned": true }
```

- [ ] **Step 5: Add the hot bindings to `default.json`** — extend `overrides` (and add the campfire/candle/furnace/redstone/lightning predicate keys). The full file becomes:

```json
{
  "tags": [
    { "tag": "c:stones", "material": "orge:generic_solid" },
    { "tag": "minecraft:candles[lit=true]", "material": "orge:candle" }
  ],
  "overrides": {
    "minecraft:air":   "orge:air",
    "minecraft:water": "orge:water",
    "minecraft:lava":  "orge:lava",
    "minecraft:ice":   "orge:ice",
    "orge:steam":      "orge:steam",

    "minecraft:fire":          "orge:fire",
    "minecraft:nether_portal": "orge:portal",
    "minecraft:torch":         "orge:torch",
    "minecraft:wall_torch":    "orge:torch",
    "minecraft:lantern":       "orge:lantern",
    "minecraft:magma_block":   "orge:magma",
    "minecraft:glowstone":     "orge:glowstone",
    "minecraft:redstone_block":"orge:redstone_block",

    "minecraft:campfire[lit=true]":          "orge:campfire",
    "minecraft:furnace[lit=true]":           "orge:furnace_lit",
    "minecraft:blast_furnace[lit=true]":     "orge:furnace_lit",
    "minecraft:smoker[lit=true]":            "orge:furnace_lit",
    "minecraft:redstone_torch[lit=true]":      "orge:redstone_torch",
    "minecraft:redstone_wall_torch[lit=true]": "orge:redstone_torch",
    "minecraft:redstone_lamp[lit=true]":     "orge:redstone_lamp",
    "minecraft:copper_bulb[lit=true]":           "orge:copper_bulb",
    "minecraft:exposed_copper_bulb[lit=true]":   "orge:copper_bulb",
    "minecraft:weathered_copper_bulb[lit=true]": "orge:copper_bulb",
    "minecraft:oxidized_copper_bulb[lit=true]":  "orge:copper_bulb",
    "minecraft:waxed_copper_bulb[lit=true]":           "orge:copper_bulb",
    "minecraft:waxed_exposed_copper_bulb[lit=true]":   "orge:copper_bulb",
    "minecraft:waxed_weathered_copper_bulb[lit=true]": "orge:copper_bulb",
    "minecraft:waxed_oxidized_copper_bulb[lit=true]":  "orge:copper_bulb",
    "minecraft:lightning_rod[powered=true]": "orge:lightning_rod",

    "minecraft:repeater[powered=true]":        "orge:powered_redstone",
    "minecraft:comparator[powered=true]":      "orge:powered_redstone",
    "minecraft:observer[powered=true]":        "orge:powered_redstone",
    "minecraft:lever[powered=true]":           "orge:powered_redstone",
    "minecraft:tripwire_hook[powered=true]":   "orge:powered_redstone",
    "minecraft:powered_rail[powered=true]":    "orge:powered_redstone",
    "minecraft:detector_rail[powered=true]":   "orge:powered_redstone",
    "minecraft:activator_rail[powered=true]":  "orge:powered_redstone",
    "minecraft:note_block[powered=true]":      "orge:powered_redstone",
    "minecraft:stone_button[powered=true]":            "orge:powered_redstone",
    "minecraft:polished_blackstone_button[powered=true]":"orge:powered_redstone",
    "minecraft:oak_button[powered=true]":      "orge:powered_redstone",
    "minecraft:spruce_button[powered=true]":   "orge:powered_redstone",
    "minecraft:birch_button[powered=true]":    "orge:powered_redstone",
    "minecraft:jungle_button[powered=true]":   "orge:powered_redstone",
    "minecraft:acacia_button[powered=true]":   "orge:powered_redstone",
    "minecraft:dark_oak_button[powered=true]": "orge:powered_redstone",
    "minecraft:mangrove_button[powered=true]": "orge:powered_redstone",
    "minecraft:cherry_button[powered=true]":   "orge:powered_redstone",
    "minecraft:bamboo_button[powered=true]":   "orge:powered_redstone",
    "minecraft:crimson_button[powered=true]":  "orge:powered_redstone",
    "minecraft:warped_button[powered=true]":   "orge:powered_redstone",
    "minecraft:stone_pressure_plate[powered=true]":             "orge:powered_redstone",
    "minecraft:polished_blackstone_pressure_plate[powered=true]":"orge:powered_redstone",
    "minecraft:oak_pressure_plate[powered=true]":      "orge:powered_redstone",
    "minecraft:spruce_pressure_plate[powered=true]":   "orge:powered_redstone",
    "minecraft:birch_pressure_plate[powered=true]":    "orge:powered_redstone",
    "minecraft:jungle_pressure_plate[powered=true]":   "orge:powered_redstone",
    "minecraft:acacia_pressure_plate[powered=true]":   "orge:powered_redstone",
    "minecraft:dark_oak_pressure_plate[powered=true]": "orge:powered_redstone",
    "minecraft:mangrove_pressure_plate[powered=true]": "orge:powered_redstone",
    "minecraft:cherry_pressure_plate[powered=true]":   "orge:powered_redstone",
    "minecraft:bamboo_pressure_plate[powered=true]":   "orge:powered_redstone",
    "minecraft:crimson_pressure_plate[powered=true]":  "orge:powered_redstone",
    "minecraft:warped_pressure_plate[powered=true]":   "orge:powered_redstone",
    "minecraft:piston[extended=true]":         "orge:powered_redstone",
    "minecraft:sticky_piston[extended=true]":  "orge:powered_redstone",
    "minecraft:dispenser[triggered=true]":     "orge:powered_redstone",
    "minecraft:dropper[triggered=true]":       "orge:powered_redstone",
    "minecraft:crafter[triggered=true]":       "orge:powered_redstone",
    "minecraft:redstone_wire[power>0]":             "orge:powered_redstone",
    "minecraft:daylight_detector[power>0]":         "orge:powered_redstone",
    "minecraft:target[power>0]":                    "orge:powered_redstone",
    "minecraft:light_weighted_pressure_plate[power>0]": "orge:powered_redstone",
    "minecraft:heavy_weighted_pressure_plate[power>0]": "orge:powered_redstone",
    "minecraft:sculk_sensor[power>0]":              "orge:powered_redstone",
    "minecraft:calibrated_sculk_sensor[power>0]":   "orge:powered_redstone"
  }
}
```

> Note: JSON object keys must be unique; every key above is a distinct block id (the predicate
> is part of the key string). If a block id has no predicate it is matched unconditionally.

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.HotSourceBindingsTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/data/orge/orge/materials/ core/src/main/resources/data/orge/orge/bindings/default.json core/src/test/java/net/rainbowcreation/orge/material/HotSourceBindingsTest.java
git commit -m "feat(data): hot heat-source materials + bindings (block-id, lit/powered)"
```

---

## Task 13: Source data — cold sources

**Files:**
- Create: `core/src/main/resources/data/orge/orge/materials/{end_rod,soul_fire,blue_ice,soul_lantern,soul_campfire}.json`
- Modify: `core/src/main/resources/data/orge/orge/bindings/default.json`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/ColdSourceBindingsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ColdSourceBindingsTest {

    private static JsonElement resource(String path) {
        try (InputStream in = ColdSourceBindingsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static PropertyView view(Map<String, String> m) { return m::get; }
    private static final MaterialBindings.TagMembership NO_TAGS = (t, b) -> false;

    private static Material material(String id) {
        return MaterialCodec.fromJson(Identifier.fromNamespaceAndPath("orge", id),
                resource("/data/orge/orge/materials/" + id + ".json"));
    }
    private static MaterialBindings bindings() {
        MaterialBindings b = new MaterialBindings();
        MaterialData.loadBindings(List.of(resource("/data/orge/orge/bindings/default.json")), b);
        return b;
    }

    @Test
    void blueIcePinnedCold() {
        Material m = material("blue_ice");
        assertTrue(m.pinned());
        assertEquals(250f, m.defaultTemperature(), 1e-3f);
        assertEquals(Identifier.fromNamespaceAndPath("orge", "blue_ice"),
                bindings().materialFor(Identifier.fromNamespaceAndPath("minecraft", "blue_ice"),
                        PropertyView.EMPTY, NO_TAGS));
    }

    @Test
    void endRodAndSoulFireBind() {
        MaterialBindings b = bindings();
        assertEquals(Identifier.fromNamespaceAndPath("orge", "end_rod"),
                b.materialFor(Identifier.fromNamespaceAndPath("minecraft", "end_rod"), PropertyView.EMPTY, NO_TAGS));
        assertEquals(Identifier.fromNamespaceAndPath("orge", "soul_fire"),
                b.materialFor(Identifier.fromNamespaceAndPath("minecraft", "soul_fire"), PropertyView.EMPTY, NO_TAGS));
    }

    @Test
    void litSoulCampfireBinds() {
        Identifier sc = Identifier.fromNamespaceAndPath("minecraft", "soul_campfire");
        assertEquals(Identifier.fromNamespaceAndPath("orge", "soul_campfire"),
                bindings().materialFor(sc, view(Map.of("lit", "true")), NO_TAGS));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.ColdSourceBindingsTest" --rerun-tasks`
Expected: FAIL — cold materials & bindings absent.

- [ ] **Step 3: Create the cold source material files** (template, varying `default_temperature`):

| file | `default_temperature` |
|---|---|
| `end_rod.json` | 250 |
| `soul_fire.json` | 250 |
| `blue_ice.json` | 250 |
| `soul_lantern.json` | 260 |
| `soul_campfire.json` | 255 |

Example (`blue_ice.json`):

```json
{ "thermal_conductivity": 1.0, "heat_capacity": 1000, "default_mass": 100,
  "default_temperature": 250, "pinned": true }
```

- [ ] **Step 4: Add the cold bindings to `default.json` `overrides`** (insert these keys alongside the hot ones):

```json
    "minecraft:end_rod":      "orge:end_rod",
    "minecraft:soul_fire":    "orge:soul_fire",
    "minecraft:blue_ice":     "orge:blue_ice",
    "minecraft:soul_lantern": "orge:soul_lantern",
    "minecraft:soul_campfire[lit=true]": "orge:soul_campfire",
```

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.ColdSourceBindingsTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/resources/data/orge/orge/materials/ core/src/main/resources/data/orge/orge/bindings/default.json core/src/test/java/net/rainbowcreation/orge/material/ColdSourceBindingsTest.java
git commit -m "feat(data): cold heat-source materials + bindings"
```

---

## Task 14: OrgeCommandLogic — Locale.ROOT on every String.format

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLocaleTest.java`

- [ ] **Step 1: Write the failing test** (forces a comma-decimal locale and asserts `.`-decimal output)

```java
package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class OrgeCommandLocaleTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restore() { Locale.setDefault(original); }

    /** A read source returning a fixed-temperature ambient view, so GET formats a float. */
    private static OrgeCommandLogic logic() {
        ThermalReadSource src = (dim, key) -> Optional.of(new SectionView() {
            public float tempAt(int cell) { return 285.0f; }
            public float massAt(int cell) { return 1000.0f; }
            public net.rainbowcreation.orge.section.SectionData.Form form() {
                return net.rainbowcreation.orge.section.SectionData.Form.UNIFORM;
            }
            public boolean ambient() { return true; }
        });
        return new OrgeCommandLogic(List.of(src), NoWriteSink.INSTANCE, () -> 8);
    }

    @Test
    void getUsesDotDecimalUnderCommaLocale() {
        Locale.setDefault(Locale.GERMANY); // comma decimal separator
        OrgeCommandLogic.Request r = new OrgeCommandLogic.Request(
                OrgeCommandLogic.Op.GET,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"),
                0, 64, 0, 0, 64, 0, null, null, true, null, -64, 320);
        OrgeCommandLogic.Response resp = logic().run(r);
        assertTrue(resp.ok());
        String line = resp.lines().get(0);
        assertTrue(line.contains("285.00 K"), "expected dot-decimal, got: " + line);
        assertFalse(line.contains("285,00"), "comma decimal leaked: " + line);
    }

    private enum NoWriteSink implements ThermalWriteSink {
        INSTANCE;
        public boolean isLoaded(Identifier dim, net.rainbowcreation.orge.section.SubchunkKey key) { return false; }
        public void writeTemp(Identifier dim, net.rainbowcreation.orge.section.SubchunkKey key, int cell, float t) {}
        public void writeMass(Identifier dim, net.rainbowcreation.orge.section.SubchunkKey key, int cell, float m) {}
    }
}
```

> Interface shapes verified against Topic A: `ThermalReadSource.section(Identifier, SubchunkKey)
> → Optional<SectionView>`; `SectionView` = `tempAt(int)`, `massAt(int)`, `form()`→`SectionData.Form`,
> `ambient()`; `ThermalWriteSink` = `isLoaded/writeTemp/writeMass`; `ReadRangeProvider` =
> `sectionReadRange()`. `Request` is the 14-arg record. The fakes above already match.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLocaleTest" --rerun-tasks`
Expected: FAIL — output contains `285,00 K` under the German locale.

- [ ] **Step 3: Implement** — add the import and `Locale.ROOT` to every `String.format`:

Add import (after the existing imports):

```java
import java.util.Locale;
```

Then prefix every `String.format(` in the file with `Locale.ROOT, `. There are 11 call sites (GET, SECTION ×4, SET ×2, FILL ×3, `yError` ×1). Each becomes `String.format(Locale.ROOT, "…", …)`. Example for GET:

```java
        return Response.ok(String.format(Locale.ROOT,
                "cell (%d,%d,%d) [%s]: %.2f K (%.2f C), %.1f kg, form=%s",
                r.x1(), r.y1(), r.z1(), r.dimension(), t, t - 273.15f, m, formStr));
```

(Apply the identical `Locale.ROOT, ` insertion to all 11 sites — including the two inside `set`, the three inside `fill`, and `yError`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLocaleTest" --rerun-tasks`
Expected: PASS. Run the existing `OrgeCommandLogicTest` to confirm no regression:
`JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest" --rerun-tasks` → PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLocaleTest.java
git commit -m "fix(command): Locale.ROOT on all String.format (non-EN portability)"
```

---

## Task 15: Audit scenario — headless integration test

**Files:**
- Test: `core/src/test/java/net/rainbowcreation/orge/AuditScenarioTest.java`

Validates the **new orchestration contract** deterministically without a live server: seed →
diffuse → phase plan → re-pin, repeated. Kernel numerical accuracy is already covered by the
engine FFI tests; this proves seeding + pinning + phase ordering compose so that water next to
a pinned lava cell boils to `orge:steam` while lava stays pinned and never freezes. Uses a
simple deterministic 1-D diffusion as the "engine" so the test is hermetic.

- [ ] **Step 1: Write the test**

```java
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
        return new Material(LAVA, 1.5f, 1450f, 0f, 3100f, 0f,
                Float.POSITIVE_INFINITY, 1000f, null, STONE, null, 1400f, true);
    }
    // water: boils (>373.15) -> orge:steam, freezes (<273.15) -> ice; not pinned
    private static Material water() {
        return new Material(WATER, 0.6f, 4186f, 0f, 1000f, 0.018f,
                373.15f, 273.15f, ORGE_STEAM, ICE, null);
    }
    private static Material steam() {
        return new Material(STEAM, 0.02f, 2000f, 0f, 1f, 0.018f,
                Float.POSITIVE_INFINITY, 373.15f, null, WATER, null);
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
                Optional<Identifier> target = PhaseRule.targetBlock(t[i], matAt.apply(i));
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
```

> The 1-D diffusion is illustrative (it exercises the *contract*, not the production kernel).
> If the reviewer prefers, this can later be promoted to drive the real `OrgeEngine` over a
> single 16³ section; for v1 the deterministic model is sufficient and hermetic.

- [ ] **Step 2: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.AuditScenarioTest" --rerun-tasks`
Expected: PASS (water boils within 200 s; lava stays pinned).

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/net/rainbowcreation/orge/AuditScenarioTest.java
git commit -m "test(audit): headless seed->step->phase->repin scenario (water boils, lava holds)"
```

---

## Task 16: Final verification + push

**Files:** none (verification only)

- [ ] **Step 1: Full core test suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: BUILD SUCCESSFUL — all green, no regressions.

- [ ] **Step 2: All three loaders compile**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Full build**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Push**

```bash
git push origin rebuild
```

- [ ] **Step 5: Memory** — add an `orge-engine-audit-bc` memory (sibling style to the other ORGE track memories) summarizing: shared Material `default_temperature`+`pinned`, conditional re-pin flow, biome per-cell seeding, blockstate-predicate bindings (`=`/`>`), source roster, Locale.ROOT fix, in-game demo still pending a dev client. Update `MEMORY.md` with a one-line pointer.

---

## Notes for the executor

- **In-game demo** (place water next to lava, `/orge get` to watch it climb past 373.15 K) is **pending a dev client** — there is no `runClient` in this sandbox. Seam paths are compile-verified; behavior is covered by the headless tests above. Note this in the final report; it is **not** a blocker.
- **Biome API symbol:** Task 10 assumes `ServerLevel.getBiome(BlockPos).value().getBaseTemperature()`. If the mapping differs in the dev jar, resolve the actual symbol before adjusting — do not guess.
- **Source temperatures** in the data tasks are the spec's proposals; they are tunable and do not affect the lava↔water audit.
- Reconcile real commit SHAs from `git log` before handing them to any reviewer subagent.
