# §10 Phase-2b (2/3) — Java: material three-mass data, wetting reconciler, per-species §9 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make water **visibly** spread in-game now that Plan 1's engine moves mass into air and across species: carry the three-mass model (`min_flow_mass`, `max_mass`, `gas`) from material JSON into the native LUT (air's `fullMass = 1.2`), grow `MinecraftFluidReconciler` to **place** blocks into air cells (water/lava/steam) driven by `StepResult.material()` with a level-bucket throttle, and upgrade §9 to per-species conservation with a boil-volume exemption.

**Architecture:** Plan 1 already landed the engine (`matOut` ABI, `StepResult.material()` returning `char[]`, density swap + wetting in the kernel) and the scheduler already threads `material()` through to the reconciler seam. This plan is **MAIN-only Java**. It does NOT touch C++/JNI. The material subsystem (§6) gains three data fields and the LUT builder (`BatchMarshaller.flatten`) populates the native arrays from them; the reconciler (§10) gains `air → fluid` placement keyed on the engine's reported species; §9 (`StepValidator`) gains a per-species, dual-indexed conservation pass (before by input species, after by output species, never exempted) plus an over-cap-only boil-volume exemption on the per-cell bound. Each piece is behind an existing pure seam and is unit-tested before wiring.

**Tech Stack:** Java 21, Architectury multiloader (MC 1.21.11, **Mojang mappings** — `Identifier` == `ResourceLocation`; `ServerPlayer.level()`; block placement `Block.UPDATE_CLIENTS`); Mojang DFU codecs (`com.mojang.serialization`); JUnit 5. Build env: `JAVA_HOME=/home/claude/jdk21`.

**Spec:** `docs/superpowers/specs/2026-05-30-fluid-displacement-phase2b-design.md` — this plan covers **Decisions 0, 6, 7, 12** plus the material-data parts of Decisions 0/1/2.

---

## One repository — read this first

| Repo | Path | `git add` from |
|---|---|---|
| **MAIN** | `/home/claude/ORGE` (branch `rebuild`) | run git from `/home/claude/ORGE` |

Plan 2 is **MAIN-only**. There are **no ENGINE / C++ / JNI changes** in this plan — that was all Plan 1. Do **not** `cd` into `ORGE-ENGINE` and do **not** rebuild `liborge.so` here.

**Push policy (standing authorization — do not ask):** the user tests from `origin/rebuild`, so **every task that commits ends with `git push origin rebuild`.** Each commit message ends with the trailer
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

**Plan 1 must be landed first.** This plan consumes Plan 1's symbols (see "Type consistency with Plan 1" below). If `StepResult` does not yet have a 3-arg form or `material()` accessor, stop and land Plan 1's MAIN tasks (8–11) first.

## Conventions for every task

- **One test class:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "<FQCN>" --rerun-tasks`
- **All 3 loaders compile (the gate):** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
- **Full core suite:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
- TDD per task: write the failing test → run it to confirm it fails → implement the minimal code → run it to pass → run the loader gate (when production code changed) → commit **and push**.
- Commit per task; push `origin rebuild` in the same commit step.

## Type consistency with Plan 1 (CRITICAL — this plan is reviewed against it)

These symbols come from Plan 1 and are used verbatim here. Do not rename them.

| Symbol | Shape | Where |
|---|---|---|
| `StepResult` | `record StepResult(float[] temperature, float[] mass, char[] material)` with a 2-arg back-compat ctor `StepResult(float[], float[])` → `material == null` | `core/.../engine/StepResult.java` |
| `StepResult.material()` | returns `char[]` (the per-cell species, the engine's `matOut`) — **NOT** `matOut`, **NOT** `short[]` | accessor |
| native out array | called `matOut` in C++/JNI only — never named in Java | (Plan 1, not touched here) |
| Material JSON keys | `min_flow_mass`, `max_mass`, `gas` (snake_case, like the existing keys) | material JSON + `MaterialCodec` |
| Java `Material` accessors | `minFlowMass()`, `maxMass()`, `gas()` | `Material.java` |
| native LUT field names | `minFlow`, `maxMass`, `gas` (+ existing `fullMass` = `default_mass`) | `BatchMarshaller.Flat` / native arrays |
| Air index-0 `fullMass` | must be **1.2** in the native LUT (Plan-1 Task-5 reads `lut.fullMass[0]` for the density-swap air label) | `BatchMarshaller.flatten` |

**Invariants baked into this slice (Spec Decision 0):**
- `min_flow_mass ≤ default_mass ≤ max_mass`.
- For **every current material** set `max_mass == default_mass` (inert until the compressible-gas track).
- water/lava get a **real** `min_flow_mass` floor; air/void floors stay **0**.
- **gas types must have `min_flow_mass > 0`** (crash-guard invariant) — **but `air` is the untracked ambient exception** (floor 0, never seeded). `steam` is marked `gas: true` with a positive floor.
- **Never special-case `air` by identity** in logic the gas path shares; `steam` is a tracked gas exercising the general path.

---

## File structure

| File | Action | Responsibility |
|---|---|---|
| `core/src/main/java/net/rainbowcreation/orge/material/Material.java` | modify | record gains `minFlowMass`, `maxMass`, `gas`; new full ctor + back-compat ctors default them. |
| `core/src/main/java/net/rainbowcreation/orge/material/MaterialCodec.java` | modify | `BodyData` + `BODY_CODEC` gain `min_flow_mass`, `max_mass`, `gas` JSON keys (optional, defaulted). |
| `core/src/main/resources/data/orge/orge/materials/{water,lava,steam}.json` | modify | add floors/caps; `steam` gets `gas: true` + floor. (air/solid/ice need no change — see Task 2.) |
| `core/src/main/java/net/rainbowcreation/orge/engine/BatchMarshaller.java` | modify | `Flat` + `flatten` build native `minFlow`/`maxMass`/`gas` arrays; **air `fullMass`** label at index 0. |
| `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java` | modify | `orgeStep` native decl gains the 3 new LUT arrays; pass them through. |
| `core/src/main/java/net/rainbowcreation/orge/phase/FluidReconcileLogic.java` | modify | add a `levelBucket(int)` helper for the reconcile throttle (pure). |
| `core/src/main/java/net/rainbowcreation/orge/phase/MinecraftFluidReconciler.java` | modify | `air → fluid` placement keyed on `material()`; steam placement; level-bucket throttle; keep §7 whitelist + `UPDATE_CLIENTS`. |
| `core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java` | modify | per-species conservation in one O(N) pass with a **dual-index** sum (before by input species, after by output species, never exempted); per-cell `max_mass` bound on the output species, exempting cells over their own cap (boil-volume). |
| `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java` | modify | call the per-species §9 overload (pass input `matIx` + engine `material()`); over-cap `cleanMass` restore so the boil deposit isn't clamped/destroyed. |

**Task ordering rationale:** material data + LUT (Tasks 1–3) land first so the reconciler (Tasks 4–5) has real species/caps to read. §9 (Task 6) is independent — it depends only on the per-species LUT data from Task 1, not on the reconciler — and can be reviewed in isolation.

---

## Task 1 [MAIN]: `Material` + `MaterialCodec` gain `min_flow_mass`, `max_mass`, `gas`

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/Material.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/MaterialCodec.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialThreeMassTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/orge/material/MaterialThreeMassTest.java`:

```java
package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialThreeMassTest {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "w");

    @Test
    void legacyConstructorsDefaultThreeMassFields() {
        // Back-compat ctor: min_flow_mass=0, max_mass falls back to default_mass, gas=false.
        Material m = new Material(ID, 1f, 2f, 0f, 1000f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
        assertEquals(0f, m.minFlowMass(), 0f);
        assertEquals(1000f, m.maxMass(), 0f, "max_mass defaults to default_mass");
        assertFalse(m.gas());
    }

    @Test
    void codecParsesThreeMassFields() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000,
                  "viscosity": 0.001, "fluid": true,
                  "min_flow_mass": 125, "max_mass": 1000 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(125f, m.minFlowMass(), 1e-6f);
        assertEquals(1000f, m.maxMass(), 1e-6f);
        assertFalse(m.gas());
    }

    @Test
    void codecParsesGasTrueAndDefaultsMaxMassToDefaultMass() {
        String json = """
                { "thermal_conductivity": 0.025, "heat_capacity": 2080, "default_mass": 0.6,
                  "fluid": true, "gas": true, "min_flow_mass": 0.6 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertTrue(m.gas());
        assertEquals(0.6f, m.minFlowMass(), 1e-6f);
        // max_mass absent -> falls back to default_mass (0.6) so max_mass == default_mass holds.
        assertEquals(0.6f, m.maxMass(), 1e-6f);
    }

    @Test
    void codecDefaultsWhenAbsent() {
        String json = """
                { "thermal_conductivity": 2.0, "heat_capacity": 840, "default_mass": 2500 }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertEquals(0f, m.minFlowMass(), 0f);
        assertEquals(2500f, m.maxMass(), 0f, "absent max_mass -> default_mass");
        assertFalse(m.gas());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialThreeMassTest" --rerun-tasks`
Expected: compile FAIL — `Material` has no `minFlowMass()` / `maxMass()` / `gas()` accessors.

- [ ] **Step 3: Add the three fields to `Material`**

In `core/src/main/java/net/rainbowcreation/orge/material/Material.java`, extend the record component list. Append the three new components **after** `fluid` so existing positional call sites that pass all fields are the only ones affected, and add `@param` lines:

```java
 * @param fluid                when true this material participates in Phase-2 mass-conservative flow
 * @param minFlowMass          kg, flow floor (cohesion); a cell at/below this no longer donates (0 = no floor)
 * @param maxMass              kg, per-cell capacity cap (>= defaultMass); 0 means "fall back to defaultMass"
 * @param gas                  when true this material is a gas phase (buoyancy / cross-species rules)
 */
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
        boolean pinned,
        boolean fluid,
        float minFlowMass,
        float maxMass,
        boolean gas
) {
```

Add a **canonical-compact accessor** so a 0/absent `maxMass` reports `defaultMass` (keeps `max_mass == default_mass` true even when the field is left blank). Place it just below the closing `)` of the record header, before the existing ctors:

```java
    /**
     * Per-cell capacity cap (kg). Stored 0 means "unset" → falls back to {@link #defaultMass()}
     * so the {@code max_mass == default_mass} invariant holds for every material this slice.
     */
    @Override
    public float maxMass() {
        return maxMass > 0f ? maxMass : defaultMass;
    }
```

Update the **existing** back-compat ctors to default the three new fields. The 11-arg ctor (no pin, not a fluid):

```java
    /** Backward-compatible constructor: no natural/pin temperature, not a source, not a fluid. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float boilingPoint, float freezingPoint,
                    Identifier boilingTarget, Identifier freezingTarget,
                    Identifier representativeBlock) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock,
                Float.NaN, false, false, 0f, 0f, false);
    }
```

The 13-arg ctor (pin temperature + pinned flag, not a fluid):

```java
    /** Constructor with pin temperature + pinned flag, not a fluid. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float boilingPoint, float freezingPoint,
                    Identifier boilingTarget, Identifier freezingTarget,
                    Identifier representativeBlock, float defaultTemperature, boolean pinned) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock,
                defaultTemperature, pinned, false, 0f, 0f, false);
    }
```

Add a new **14-arg back-compat ctor** that defaults only the three new fields, so existing call sites that pass `fluid` (e.g. `StepValidatorMassTest.water()` which calls the full old 14-arg shape `(..., Float.NaN, false, true)`) keep compiling:

```java
    /** Constructor with pin temperature + pinned + fluid, three-mass fields defaulted. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float boilingPoint, float freezingPoint,
                    Identifier boilingTarget, Identifier freezingTarget,
                    Identifier representativeBlock, float defaultTemperature,
                    boolean pinned, boolean fluid) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock,
                defaultTemperature, pinned, fluid, 0f, 0f, false);
    }
```

- [ ] **Step 4: Add the JSON keys to `MaterialCodec`**

In `core/src/main/java/net/rainbowcreation/orge/material/MaterialCodec.java`, extend `BodyData` with the three fields (append after `fluid`):

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
            boolean pinned,
            boolean fluid,
            float minFlowMass,
            float maxMass,
            boolean gas
    ) {}
```

Extend `BODY_CODEC` — add the three optional fields after the `fluid` group entry (RecordCodecBuilder supports up to 16 fields):

```java
                    Codec.BOOL.optionalFieldOf("fluid", false)
                            .forGetter(BodyData::fluid),
                    Codec.FLOAT.optionalFieldOf("min_flow_mass", 0f)
                            .forGetter(BodyData::minFlowMass),
                    Codec.FLOAT.optionalFieldOf("max_mass", 0f)
                            .forGetter(BodyData::maxMass),
                    Codec.BOOL.optionalFieldOf("gas", false)
                            .forGetter(BodyData::gas)
            ).apply(instance, BodyData::new)
    );
```

Add the **gas crash-guard invariant** to `fromJson`, next to the existing pin guard (a tracked gas must declare a positive floor so it is never seeded at 0 — air is exempt because air is never declared a gas):

```java
        if (bd.gas() && !(bd.minFlowMass() > 0f)) {
            throw new IllegalArgumentException(
                    "material " + id + ": gas=true requires min_flow_mass > 0 (crash-guard: a gas cell "
                            + "must never have zero density)");
        }
```

Pass the three new fields into the `Material` constructor call at the end of `fromJson` (the canonical 17-arg ctor):

```java
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
                bd.pinned(),
                bd.fluid(),
                bd.minFlowMass(),
                bd.maxMass(),
                bd.gas()
        );
```

Update the `MaterialCodec` class javadoc "Optional with defaults" line to mention the new keys:

```java
 *   <li><b>Optional with defaults:</b>
 *     {@code viscosity} → 0, {@code molar_mass} → 0,
 *     {@code boiling_point} → +∞, {@code freezing_point} → -∞,
 *     {@code default_temperature} → NaN (absent), {@code pinned} → false, {@code fluid} → false,
 *     {@code min_flow_mass} → 0, {@code max_mass} → 0 (= default_mass), {@code gas} → false</li>
```

- [ ] **Step 5: Run to verify it passes; then the full suite + loader gate**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialThreeMassTest" --rerun-tasks`
Expected: PASS.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green — existing `MaterialFluidTest` and `StepValidatorMassTest.water()` use the 11-/14-arg back-compat ctors which now default the three fields.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile.

- [ ] **Step 6: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/material/Material.java \
        core/src/main/java/net/rainbowcreation/orge/material/MaterialCodec.java \
        core/src/test/java/net/rainbowcreation/orge/material/MaterialThreeMassTest.java
git commit -m "feat(material): three-mass model fields min_flow_mass/max_mass/gas (+gas crash-guard)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 2 [MAIN]: material JSON — water/lava floors, steam gas+floor

**Files:**
- Modify: `core/src/main/resources/data/orge/orge/materials/water.json`
- Modify: `core/src/main/resources/data/orge/orge/materials/lava.json`
- Modify: `core/src/main/resources/data/orge/orge/materials/steam.json`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialThreeMassJsonTest.java` (create)

> No change to `air.json`, `generic_solid.json`, `ice.json`, or any other material file: air/void floors **stay 0** (air is the untracked ambient exception), and every solid keeps `max_mass` absent (→ `default_mass`). Only the two real fluids and the one tracked gas get the new keys.

- [ ] **Step 1: Write the failing test** — loads the actual datapack JSON via the codec and asserts the values.

Create `core/src/test/java/net/rainbowcreation/orge/material/MaterialThreeMassJsonTest.java`:

```java
package net.rainbowcreation.orge.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MaterialThreeMassJsonTest {

    private static Material load(String name) throws Exception {
        String path = "/data/orge/orge/materials/" + name + ".json";
        try (InputStream in = MaterialThreeMassJsonTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            JsonElement body = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return MaterialCodec.fromJson(Identifier.fromNamespaceAndPath("orge", name), body);
        }
    }

    @Test
    void waterHasFloorAndCapEqualToDefaultMass() throws Exception {
        Material w = load("water");
        assertTrue(w.fluid());
        assertEquals(125f, w.minFlowMass(), 1e-4f);
        assertEquals(1000f, w.maxMass(), 1e-4f, "max_mass == default_mass this slice");
        assertEquals(1000f, w.defaultMass(), 1e-4f);
        assertFalse(w.gas());
    }

    @Test
    void lavaHasFloorAndCapEqualToDefaultMass() throws Exception {
        Material l = load("lava");
        assertEquals(400f, l.minFlowMass(), 1e-4f);
        assertEquals(3100f, l.maxMass(), 1e-4f);
        assertFalse(l.gas());
    }

    @Test
    void steamIsGasWithPositiveFloorAndCapEqualToDefaultMass() throws Exception {
        Material s = load("steam");
        assertTrue(s.gas(), "steam is a tracked gas");
        assertTrue(s.fluid(), "a tracked gas participates in advection");
        assertTrue(s.minFlowMass() > 0f, "gas crash-guard: positive floor");
        assertEquals(0.6f, s.maxMass(), 1e-4f, "max_mass == default_mass this slice");
        assertEquals(0.6f, s.defaultMass(), 1e-4f);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialThreeMassJsonTest" --rerun-tasks`
Expected: FAIL — `water.minFlowMass()` is 0 (key absent), `steam.gas()` is false (and currently `steam.fluid()` is false too).

- [ ] **Step 3: Edit `water.json`** — add the flow floor + explicit cap.

```json
{
  "thermal_conductivity": 0.6,
  "heat_capacity": 4186,
  "default_mass": 1000,
  "molar_mass": 0.018,
  "viscosity": 0.001,
  "fluid": true,
  "min_flow_mass": 125,
  "max_mass": 1000,
  "freezing_point": 273.15,
  "freezing_target": "minecraft:ice",
  "boiling_point": 373.15,
  "boiling_target": "orge:steam",
  "representative_block": "minecraft:water"
}
```

- [ ] **Step 4: Edit `lava.json`** — add the flow floor + explicit cap.

```json
{
  "thermal_conductivity": 1.5,
  "heat_capacity": 1450,
  "default_mass": 3100,
  "molar_mass": 0.060,
  "viscosity": 100,
  "fluid": true,
  "min_flow_mass": 400,
  "max_mass": 3100,
  "freezing_point": 1000,
  "freezing_target": "minecraft:stone",
  "representative_block": "minecraft:lava",
  "default_temperature": 1400,
  "pinned": true
}
```

- [ ] **Step 5: Edit `steam.json`** — mark it a tracked gas (`fluid: true`, `gas: true`) with a positive floor and an explicit cap equal to its resting density.

```json
{
  "thermal_conductivity": 0.025,
  "heat_capacity": 2080,
  "default_mass": 0.6,
  "molar_mass": 0.018,
  "fluid": true,
  "gas": true,
  "min_flow_mass": 0.6,
  "max_mass": 0.6,
  "freezing_point": 373.15,
  "freezing_target": "minecraft:water",
  "representative_block": "orge:steam"
}
```

> Steam's floor equals its resting density (`min_flow_mass == default_mass == max_mass == 0.6`): this slice keeps `max_mass == default_mass`, so steam behaves as a light fluid that **rises via the density swap** (Plan-1 Task 5) rather than compressing — the compressible-gas track (deferred) is where `max_mass > default_mass` lights up. The positive floor satisfies the crash-guard.

- [ ] **Step 6: Run to verify it passes; then the full suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialThreeMassJsonTest" --rerun-tasks`
Expected: PASS.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green. (Note: `steam` flipping to `fluid: true` means steam cells now advect — that is intended; no existing test pins steam as a non-fluid.)

- [ ] **Step 7: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/resources/data/orge/orge/materials/water.json \
        core/src/main/resources/data/orge/orge/materials/lava.json \
        core/src/main/resources/data/orge/orge/materials/steam.json \
        core/src/test/java/net/rainbowcreation/orge/material/MaterialThreeMassJsonTest.java
git commit -m "feat(material-data): water/lava flow floors + caps; steam is a tracked gas with a floor

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 3 [MAIN]: LUT builder populates `minFlow`/`maxMass`/`gas`; air `fullMass = 1.2` at index 0

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/BatchMarshaller.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/BatchMarshallerLutTest.java` (create)

> **The native LUT builder is `BatchMarshaller.flatten()`** (confirmed: it already builds `cond`/`heatCap`/`visc`/`fullMass`/`fluid` from each `Material`). `MaterialLut` (the index allocator) is **not** the array builder and is not touched here.
>
> **Air index-0 `fullMass` subtlety:** index 0 of the LUT is the `MaterialLut.VOID` sentinel whose `defaultMass` is **0**, but Plan-1 Task-5's kernel reads `lut.fullMass[0]` as the **air density label (1.2)** for the density swap. So `flatten` must write **1.2** into `fullMass[0]` regardless of the index-0 material's `defaultMass`. This is the one place air's density label is materialized; it is general (a constant `AIR_DENSITY`), not an `air`-by-identity branch in physics logic.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/orge/engine/BatchMarshallerLutTest.java`:

```java
package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.MaterialLut;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchMarshallerLutTest {

    private static Material water() {
        // canonical 17-arg ctor: ..., fluid=true, minFlow=125, maxMass=1000, gas=false
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                0.6f, 4186f, 0.001f, 1000f, 0.018f,
                373.15f, 273.15f, null, null,
                Identifier.fromNamespaceAndPath("minecraft", "water"),
                Float.NaN, false, true, 125f, 1000f, false);
    }

    private static Material steam() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "steam"),
                0.025f, 2080f, 0f, 0.6f, 0.018f,
                Float.POSITIVE_INFINITY, 373.15f, null, null,
                Identifier.fromNamespaceAndPath("orge", "steam"),
                Float.NaN, false, true, 0.6f, 0.6f, true);
    }

    /** A minimal StepTask batch so flatten() runs; geometry content is irrelevant to the LUT arrays. */
    private static StepTask emptyTask() {
        char[] mat = new char[BatchMarshaller.SEC_N];      // all VOID (index 0)
        float[] mass = new float[BatchMarshaller.SEC_N];
        float[] temp = new float[BatchMarshaller.SEC_N];
        int f = NeighborHalo.FACE_CELLS;
        // NeighborHalo is an 18-array flat record: 6 temp faces, 6 mat faces, 6 mass faces.
        NeighborHalo halo = new NeighborHalo(
                new float[f], new float[f], new float[f], new float[f], new float[f], new float[f],
                new char[f],  new char[f],  new char[f],  new char[f],  new char[f],  new char[f],
                new float[f], new float[f], new float[f], new float[f], new float[f], new float[f]);
        return new StepTask(null, mat, mass, temp, halo);
    }

    @Test
    void lutCarriesThreeMassFieldsAndAirDensityAtIndexZero() {
        List<Material> lut = List.of(MaterialLut.VOID, water(), steam());
        BatchMarshaller.Flat f = BatchMarshaller.flatten(List.of(emptyTask()), lut);

        // index 0 (VOID) gets the AIR density label 1.2 for the kernel's density swap.
        assertEquals(1.2f, f.lutFullMass()[0], 1e-4f);
        // real materials keep their default_mass as fullMass.
        assertEquals(1000f, f.lutFullMass()[1], 1e-4f);
        assertEquals(0.6f, f.lutFullMass()[2], 1e-4f);

        assertEquals(0f, f.lutMinFlow()[0], 0f, "void floor stays 0");
        assertEquals(125f, f.lutMinFlow()[1], 1e-4f);
        assertEquals(0.6f, f.lutMinFlow()[2], 1e-4f);

        assertEquals(1000f, f.lutMaxMass()[1], 1e-4f);
        assertEquals(0.6f, f.lutMaxMass()[2], 1e-4f);

        assertEquals((byte) 0, f.lutGas()[1]);
        assertEquals((byte) 1, f.lutGas()[2], "steam is gas");
    }
}
```

> If `StepTask`'s constructor / `NeighborHalo`'s constructor differ from `(key, matIx, mass, temperature, halo)` / `(tempFaces, matFaces, massFaces)`, read those records and adjust `emptyTask()` to match — the LUT assertions are the point of the test.

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.BatchMarshallerLutTest" --rerun-tasks`
Expected: compile FAIL — `Flat` has no `lutMinFlow()` / `lutMaxMass()` / `lutGas()` accessors.

- [ ] **Step 3: Add the new LUT arrays to `BatchMarshaller.Flat` + `flatten`**

In `BatchMarshaller.java`, extend the `Flat` record (append the three arrays after `lutFluid`):

```java
    /** Flat inputs for one {@code orgeStep} call. {@code matCount} = LUT size. */
    record Flat(int n, char[] matIx, float[] mass, float[] tIn,
                float[] haloT, char[] haloMat, float[] haloMass,
                float[] lutCond, float[] lutHeatCap,
                float[] lutVisc, float[] lutFullMass, byte[] lutFluid,
                float[] lutMinFlow, float[] lutMaxMass, byte[] lutGas, int matCount) {}
```

Add the `AIR_DENSITY` constant near the other constants at the top of the class:

```java
    /**
     * Air's resting density (kg/m^3), written into the LUT's index-0 {@code fullMass} slot as the
     * density-swap air label (Spec Decision 0/2; Plan-1 kernel reads {@code lut.fullMass[0]}). This is
     * a general "lightest ambient gas" constant, NOT an air-by-identity branch in the physics rule.
     */
    static final float AIR_DENSITY = 1.2f;
```

In `flatten`, build the three new arrays in the existing LUT loop and override index-0 `fullMass`:

```java
        float[] cond = new float[m];
        float[] heatCap = new float[m];
        float[] visc = new float[m];
        float[] fullMass = new float[m];
        byte[] fluid = new byte[m];
        float[] minFlow = new float[m];
        float[] maxMass = new float[m];
        byte[] gas = new byte[m];
        for (int i = 0; i < m; i++) {
            Material mat = lut.get(i);
            cond[i] = mat.thermalConductivity();
            heatCap[i] = mat.heatCapacity();
            visc[i] = mat.viscosity();
            fullMass[i] = mat.defaultMass();
            fluid[i] = mat.fluid() ? (byte) 1 : (byte) 0;
            minFlow[i] = mat.minFlowMass();
            maxMass[i] = mat.maxMass();           // canonical accessor: 0 -> defaultMass
            gas[i] = mat.gas() ? (byte) 1 : (byte) 0;
        }
        // Index 0 is the VOID/ambient sentinel; label it with air's density so the kernel's
        // density swap (Plan-1) reads a meaningful "empty cell" density rather than 0.
        fullMass[0] = AIR_DENSITY;
        return new Flat(n, matIx, mass, tIn, haloT, haloMat, haloMass,
                cond, heatCap, visc, fullMass, fluid, minFlow, maxMass, gas, m);
```

- [ ] **Step 4: Thread the new arrays through `NativeEngine.orgeStep`**

In `NativeEngine.java`, add the three arrays to the native method declaration (after `lutFluid`, before `passes` — this must match the JNI param order that Plan 1 established; if Plan 1's `orge_jni.cpp` placed `matOut` last in the OUTPUT group, the LUT inputs still precede `passes`):

```java
    private static native double orgeStep(
            int n,
            char[] matIx, float[] mass, float[] tIn,
            float[] haloT, char[] haloMat, float[] haloMass,
            float[] lutCond, float[] lutHeatCap, float[] lutVisc,
            float[] lutFullMass, byte[] lutFluid,
            float[] lutMinFlow, float[] lutMaxMass, byte[] lutGas,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut, char[] matOut);
```

> **ABI note:** Plan 1 already added `char[] matOut` as the final output param and rebuilt `liborge.so` with the 3 new LUT input arrays in `orge_jni.cpp`. Read Plan 1's final `orge_jni.cpp` signature and the post-Plan-1 `NativeEngine.orgeStep` declaration, and match this Java declaration to it **exactly** (param count + order + types). Do **not** rebuild the native lib in this plan. If after Plan 1 the declaration already lists `lutMinFlow`/`lutMaxMass`/`lutGas`, this step is a no-op for the native decl and you only wire the call below.

Update the call inside `step(...)` to pass the new arrays (and `matOut`, per Plan 1):

```java
        BatchMarshaller.Flat f = BatchMarshaller.flatten(tasks, lut);
        float[] tOut = new float[f.n() * BatchMarshaller.SEC_N];
        float[] massOut = new float[f.n() * BatchMarshaller.SEC_N];
        char[] matOut = new char[f.n() * BatchMarshaller.SEC_N];
        lastStepMillis = orgeStep(
                f.n(), f.matIx(), f.mass(), f.tIn(),
                f.haloT(), f.haloMat(), f.haloMass(),
                f.lutCond(), f.lutHeatCap(), f.lutVisc(), f.lutFullMass(), f.lutFluid(),
                f.lutMinFlow(), f.lutMaxMass(), f.lutGas(),
                passes, dtSeconds, tOut, massOut, matOut);
        List<float[]> t = BatchMarshaller.slice(tOut, f.n());
        List<float[]> mm = BatchMarshaller.sliceMass(massOut, f.n());
        List<char[]> matm = BatchMarshaller.sliceMat(matOut, f.n());
        List<StepResult> out = new ArrayList<>(f.n());
        for (int s = 0; s < f.n(); s++) out.add(new StepResult(t.get(s), mm.get(s), matm.get(s)));
        return out;
```

If Plan 1 did not add a `sliceMat`, add it to `BatchMarshaller` next to `sliceMass`:

```java
    /** Splits a flat per-cell species output ({@code n*SEC_N}) back into per-section arrays. */
    static List<char[]> sliceMat(char[] matOut, int n) {
        List<char[]> out = new ArrayList<>(n);
        for (int s = 0; s < n; s++) {
            char[] sec = new char[SEC_N];
            System.arraycopy(matOut, s * SEC_N, sec, 0, SEC_N);
            out.add(sec);
        }
        return out;
    }
```

> Reconcile this step with Plan 1's actual `NativeEngine.step(...)` body: Plan 1 may already construct `StepResult` 3-arg from a sliced `matOut`. If so, only the **LUT-array additions** (`f.lutMinFlow()`, `f.lutMaxMass()`, `f.lutGas()`) are new here; keep Plan 1's existing `matOut` slicing untouched.

- [ ] **Step 5: Run the new test, then the full suite + loader gate**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.BatchMarshallerLutTest" --rerun-tasks`
Expected: PASS.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green — `BatchMarshallerMassTest` (existing) constructs `Flat` only via `flatten`, so the wider record is invisible to it.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile.

- [ ] **Step 6: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/BatchMarshaller.java \
        core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java \
        core/src/test/java/net/rainbowcreation/orge/engine/BatchMarshallerLutTest.java
git commit -m "feat(engine-lut): marshal minFlow/maxMass/gas to native LUT; air fullMass=1.2 at index 0

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 4 [MAIN]: `FluidReconcileLogic` gains a render level-bucket (Decision 13b)

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/phase/FluidReconcileLogic.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/phase/FluidReconcileLogicTest.java` (extend)

> The throttle (Decision 13b) writes a block only when the mass crosses into a **different render level bucket**, so mass can move every step without emitting a packet. The render level *is* the bucket: `levelForFraction` already maps a fraction to one of `{REMOVE, 0, 1..7}`. The reconciler will compare the bucket implied by the **stored** block's level to the bucket implied by the **new** mass. We add a small pure helper so the bucket of an existing block state can be derived without Minecraft types in the logic layer.

- [ ] **Step 1: Write the failing test** — append to `FluidReconcileLogicTest`.

```java
    @Test
    void levelBucketIsIdentityForValidLevels() {
        // a render level IS its own bucket (REMOVE and 0..7 each their own bucket).
        assertEquals(FluidReconcileLogic.REMOVE, FluidReconcileLogic.levelBucket(FluidReconcileLogic.REMOVE));
        assertEquals(0, FluidReconcileLogic.levelBucket(0));
        assertEquals(7, FluidReconcileLogic.levelBucket(7));
    }

    @Test
    void sameBucketMeansNoWrite() {
        // two masses that both render at level 4 share a bucket -> no write needed.
        int a = FluidReconcileLogic.levelForFraction(0.5f);   // -> 4
        int b = FluidReconcileLogic.levelForFraction(0.46f);  // round((1-0.46)*7)=round(3.78)=4
        assertEquals(a, b);
        assertEquals(FluidReconcileLogic.levelBucket(a), FluidReconcileLogic.levelBucket(b));
    }

    @Test
    void crossingToRemoveIsADifferentBucket() {
        assertNotEquals(FluidReconcileLogic.levelBucket(FluidReconcileLogic.levelForFraction(0.1f)),
                        FluidReconcileLogic.levelBucket(FluidReconcileLogic.REMOVE));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.phase.FluidReconcileLogicTest" --rerun-tasks`
Expected: compile FAIL — `FluidReconcileLogic.levelBucket` does not exist.

- [ ] **Step 3: Add `levelBucket` to `FluidReconcileLogic`**

```java
    /**
     * The reconcile **render bucket** for a level (Decision 13b throttle). A render level already
     * partitions mass into discrete buckets ({@link #REMOVE}, 0, 1..7), so the bucket of a level is
     * the level itself. The reconciler writes a block only when the new mass's bucket differs from
     * the bucket the world block currently shows — mass can move within a bucket without a packet.
     */
    public static int levelBucket(int level) {
        return level;
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.phase.FluidReconcileLogicTest" --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/phase/FluidReconcileLogic.java \
        core/src/test/java/net/rainbowcreation/orge/phase/FluidReconcileLogicTest.java
git commit -m "feat(reconcile-logic): levelBucket helper for the render-level reconcile throttle

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 5 [MAIN]: `MinecraftFluidReconciler` — `air → fluid` placement keyed on `material()`, with the bucket throttle

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/phase/MinecraftFluidReconciler.java`
- (No new unit test — this class is an MC-binding adapter not exercised by JUnit; correctness is covered by the headless `AuditScenarioTest` integration suite. The pure level/bucket math is already tested in Task 4. **Verification is the loader-compile gate + the existing `AuditScenarioTest`.**)

> **Current behavior (confirmed):** the reconcile loop reads the **world block** at each cell, derives its `Material`, and `if (!material.fluid()) continue;` (L81) — so an **air** cell (air is not a fluid) is skipped entirely; it never wets. It also only ever **removes** a `LiquidBlock` (L93–98) and places at the level derived from the **current block's** material's `defaultMass`. Phase-2b changes the driver from "world block's material" to "the engine's reported species (`material()`) + the section's stored mass".

The new reconcile loop drives placement from the engine's `material()` array (the species the cell *became*), not from the world block:

- For each cell `i`, read `species = entry.task().matIx()` is the **input** species; the **output** species is `data`/`StepResult.material()`. Since the reconciler is called with the `BatchEntry` (which carries the input `StepTask`), the **output** species must be threaded in. **Plan 1 Task 9** already changed `Scheduler.writeBackResults` to `world.writeBack(entry, new StepResult(cleanT, cleanM, r.material()))`, but `reconcile(entry)` still only gets the `BatchEntry`. So this task **also passes the engine's `material()` into the reconciler**.

- [ ] **Step 1: Extend the `FluidReconciler` seam to receive the output species**

Read `core/src/main/java/net/rainbowcreation/orge/phase/FluidReconciler.java`. It currently declares `void reconcile(ThermalWorld.BatchEntry entry)`. Add an overload that also takes the engine's per-cell output species AND the step's batch LUT (`pendingMaterials`, already in scope in the scheduler), so the reconciler can resolve `outMaterial[i]` (a LUT index) to a `Material`. Default the old method for back-compat. Add `import java.util.List;` to `FluidReconciler` if absent.

```java
    /**
     * Reconcile a section's mass to vanilla render levels (DESIGN §10). {@code outMaterial} is the
     * engine's per-cell output species ({@code StepResult.material()}, the {@code matOut} array) — the
     * species each cell BECAME this step, used to know which block to place when wetting an air cell;
     * {@code outLut} is the step's batch material table that resolves those indices. When either is
     * null the reconciler falls back to the world block's material.
     */
    default void reconcile(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut) {
        reconcile(entry); // back-compat: NOOP and the world-block-only path
    }
```

Update the call site in `Scheduler.writeBackResults` (the `fluidReconciler.reconcile(entry);` line at ~L296) to pass the species + batch LUT:

```java
                fluidReconciler.reconcile(entry, r.material(), pendingMaterials);
```

> `r` here is the `StepResult` for this entry (already in scope in `writeBackResults`). `r.material()` is the engine's `matOut` (Plan 1); `pendingMaterials` is the batch LUT field the §9 gate already reads. For the conduction-only branch the reconciler is not called, unchanged.

- [ ] **Step 2: Rewrite `MinecraftFluidReconciler.reconcile` to wet air + throttle by bucket**

Replace the `reconcile(ThermalWorld.BatchEntry entry)` method body so it implements the new overload and drives placement from `outMaterial`. The full new method (implementing the `outMaterial` overload; the old single-arg method now delegates to it with `null`, inheriting the §6-style world-block fallback):

```java
    @Override
    public void reconcile(ThermalWorld.BatchEntry entry) {
        reconcile(entry, null, null);
    }

    @Override
    public void reconcile(ThermalWorld.BatchEntry entry, char[] outMaterial, List<Material> outLut) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return;
        }
        ServerLevel level = levelFor(srv, entry.dimension());
        if (level == null) {
            return;
        }
        SubchunkKey key = entry.key();
        LevelChunk chunk = LiveMaterials.loadedChunk(level, key.cx(), key.cz());
        if (chunk == null) {
            return; // unloaded since the snapshot
        }
        LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, key.sectionY());
        if (section == null) {
            return;
        }
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(key.cx(), key.cz())) {
            return;
        }
        SectionData data = store.get(key);
        ActiveMaterials.State mats = ActiveMaterials.current();

        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;

        for (int i = 0; i < SectionData.CELLS; i++) {
            BlockState current = LiveMaterials.blockStateAt(section, i);
            Material worldMaterial = LiveMaterials.materialFor(current, mats);

            // The species the cell BECAME this step (engine matOut); fall back to the world block's
            // material when the engine didn't report one (null overload / non-advection cycle).
            Material outMat = outMaterialFor(outMaterial, outLut, i, worldMaterial);

            // Reconcile a cell when EITHER the world block is a managed fluid OR the engine says it is
            // now a fluid (wetting an air cell). Skip cells that are and stay non-fluid.
            boolean worldIsFluid = worldMaterial.fluid();
            boolean becameFluid = outMat != null && outMat.fluid();
            if (!worldIsFluid && !becameFluid) {
                continue;
            }

            float mass = data.massAt(i);
            // The cap/full reference is the species the cell now holds (so a wetted air cell reads
            // water's 1000 kg full reference, not air's).
            Material levelMaterial = becameFluid ? outMat : worldMaterial;
            float f = FluidReconcileLogic.fraction(mass, levelMaterial.defaultMass());
            int renderLevel = FluidReconcileLogic.levelForFraction(f);

            // ---- level-bucket throttle (Decision 13b) ----
            int currentBucket = bucketOfWorldBlock(current);
            if (FluidReconcileLogic.levelBucket(renderLevel) == currentBucket) {
                continue; // mass moved within the same render bucket -> no packet
            }

            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockPos pos = new BlockPos(ox + x, oy + y, oz + z);

            if (renderLevel == FluidReconcileLogic.REMOVE) {
                // Only clear a cell that currently holds a managed fluid block; leave others alone.
                if (current.getBlock() instanceof LiquidBlock || isManagedGas(current)) {
                    setIfChanged(level, pos, current, Blocks.AIR.defaultBlockState());
                }
                continue;
            }

            // §7 contact whitelist: do not overwrite a block that §7 owns (water+lava→obsidian etc.).
            // The whitelist is "only place over air or over a managed fluid of the SAME representative
            // block"; never stomp a solid the phase-changer produced.
            if (!isPlaceableTarget(current, levelMaterial)) {
                continue;
            }

            Block target = representativeBlock(levelMaterial);
            if (target == null) {
                continue; // material has no representative block to place
            }
            setIfChanged(level, pos, current, stateWithLevel(target, renderLevel));
        }
    }
```

Add the supporting helpers to the class (below `representativeBlock`):

```java
    /**
     * The engine's output species for cell {@code i}, resolved through the step's batch LUT
     * ({@code outLut}, threaded in from the scheduler's {@code pendingMaterials}). Falls back to
     * {@code worldMaterial} when no engine species is available or the cell is the VOID/air sentinel
     * (index 0).
     */
    private static Material outMaterialFor(char[] outMaterial, List<Material> outLut, int i,
                                           Material worldMaterial) {
        if (outMaterial == null || outLut == null || i >= outMaterial.length) {
            return worldMaterial;
        }
        int s = outMaterial[i];
        if (s == 0 || s >= outLut.size()) {
            return worldMaterial; // air cell that received no fluid mass; world-block path decides
        }
        return outLut.get(s);
    }

    /** The render bucket the world block currently shows: REMOVE for non-fluid, else its LEVEL. */
    private static int bucketOfWorldBlock(BlockState current) {
        if (current.getBlock() instanceof LiquidBlock && current.hasProperty(LiquidBlock.LEVEL)) {
            return FluidReconcileLogic.levelBucket(current.getValue(LiquidBlock.LEVEL));
        }
        if (isManagedGas(current)) {
            return FluidReconcileLogic.levelBucket(0); // gas renders as a single full bucket
        }
        return FluidReconcileLogic.levelBucket(FluidReconcileLogic.REMOVE);
    }

    /** True when {@code current} is ORGE's managed gas block ({@code orge:steam}). */
    private static boolean isManagedGas(BlockState current) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(current.getBlock());
        return id != null && id.equals(Identifier.fromNamespaceAndPath("orge", "steam"));
    }

    /**
     * §7 contact whitelist (Decision 7): a cell is placeable only when it is currently air/replaceable
     * OR already the managed fluid/gas block for {@code material}. This refuses to overwrite a solid
     * (e.g. obsidian/stone the phase-changer produced from a water+lava contact), leaving §7 in charge.
     */
    private static boolean isPlaceableTarget(BlockState current, Material material) {
        if (current.isAir()) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock || isManagedGas(current)) {
            return true; // already a managed fluid/gas; updating its level is fine
        }
        return false; // solid or other block -> §7 / vanilla owns it, do not stomp
    }
```

> **Why `outLut`, not a LUT-index accessor:** the engine's `material()` values are indices into the **batch** LUT, which the scheduler rebuilds each step (`pendingMaterials`) and discards — it is not retained on `ActiveMaterials.State`. So rather than invent an `ActiveMaterials` LUT-index accessor, thread that same `List<Material>` (`pendingMaterials`, already in scope at the `reconcile` call) into the reconciler and resolve `outLut.get(outMaterial[i])`. That is exactly the 3-arg seam Step 1 added and the `outMaterialFor` helper above consumes.

- [ ] **Step 3: Add the supporting imports**

Add to `MinecraftFluidReconciler` (if absent): `import java.util.List;`. Confirm `FluidReconciler` also imports `java.util.List` and `net.rainbowcreation.orge.material.Material` (added in Step 1). Verify `FluidReconcilerNoopTest` still compiles: the NOOP reconciler inherits the new defaulted 3-arg overload, so no change is needed there.

- [ ] **Step 4: Compile gate + full suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile (this is the primary gate for the MC-binding class).
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green, including `FluidReconcilerNoopTest` (the NOOP reconciler inherits the defaulted overloads) and `AuditScenarioTest` (with the Plan-1 engine bundled, water above a gap now falls + spreads into air and reconciles; water next to lava still steams/obsidians via §7).

> If `AuditScenarioTest` does not yet assert wetting, that assertion is added in the integration task of whichever plan owns `AuditScenarioTest`; do not weaken existing assertions. The §7 whitelist (`isPlaceableTarget` refusing to stomp solids) is what keeps the water-next-to-lava obsidian assertion green.

- [ ] **Step 5: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/phase/MinecraftFluidReconciler.java \
        core/src/main/java/net/rainbowcreation/orge/phase/FluidReconciler.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java
git commit -m "feat(reconcile): air->fluid placement keyed on engine matOut + steam; level-bucket throttle; keep §7 whitelist

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 6 [MAIN]: §9 `StepValidator` — per-species conservation (one O(N) pass) + boil-volume exemption

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java` (call the new overload with input `matIx` + engine `material()`; over-cap `cleanMass` restore so the boil deposit survives)
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/StepValidatorMassTest.java` (extend)

> **Current §9 (confirmed):** `massConserved(after, before, fullMassBound, matIx, lut)` runs a **single 4096-cell loop**, accumulating one scalar `sumA`/`sumB` over **fluid** cells and bounding each fluid cell at `fullMassBound` (the batch-max `defaultMass`). Under wetting + cross-species swaps a water→air drain mis-labeled as lava would pass a *total* check.
>
> **Why a per-species total alone is not enough — the wetting trap (the defect this task fixes):** the natural-but-wrong design indexes BOTH sums by the cell's OUTPUT species and exempts every cell whose species changed (`outMat[c] != inMat[c]`). That orphans the donated mass on the FIRST wetting step: a water cell (1000 kg) spreads 125 kg into an adjacent air cell. The donor stays water (`in=water, out=water`, not exempt) so it contributes `sumA[water]+=875, sumB[water]+=1000`; the recipient went air→water, is **exempted** by the species-change mask, and so its 125 kg is never counted. Result: `sumA[water]=875` vs `sumB[water]=1000` → diff 125 kg, and with *many* air cells wetted in a step the orphaned total grows without bound — it readily exceeds `ε·N` (≈ 40.96 kg for a 4096-cell section), so the gate rejects and the section is **held** — water never visibly spreads, defeating this plan's headline feature. The species-change exemption is the trap: the donated mass landed in an exempted cell.
>
> **The fix — a DUAL-INDEX conservation sum with NO exemption.** The upgrade keeps the **single loop** but accumulates `before` indexed by each cell's **INPUT** species and `after` indexed by its **OUTPUT** species into two small `double[lut.size()]` arrays — and **never exempts a cell from the conservation sum**:
>
> - `sumBefore[inMat[i]] += before[i]` (only when `inMat[i]` is a tracked fluid)
> - `sumAfter[outMat[i]] += after[i]` (only when `outMat[i]` is a tracked fluid)
> - then require `|sumAfter[s] − sumBefore[s]| ≤ ε·N` for every tracked fluid species `s`.
>
> This conserves correctly across every engine operation: **wetting** (donor water→water loses 125, recipient air→water gains 125 → water `sumBefore=1000`, `sumAfter=875+125=1000` ✓); **density swap with air** (steam below air rises: lower steam→air, upper air→steam → steam `sumBefore=0.6` from the lower input, `sumAfter=0.6` from the upper output ✓); **liquid sort swap** (water/lava exchange full cells → each species conserved on its own dual index ✓); **drain** (a cell fully spreads to in-section neighbours → the neighbours' after-sums under their own output species capture the mass ✓). Because the conservation sum is **never exempted**, it is the REAL gate: a water→air drain mis-reported as lava still fails (lava's `sumAfter` would exceed its `sumBefore` while water's `sumAfter` falls short).
>
> **The per-cell `max_mass` bound is separate and is the ONLY thing ever exempted (Decision 12 boil-volume).** Each fluid cell is bounded on its OUTPUT species, `after[i] ∈ [−ε, maxMass(outMat[i]) + ε]`. A §7 boil deposits ~1000 kg of steam into one cell (over steam's 0.6 cap) via the engine's species-flip; the advection pass relaxes it over the next several steps. The exemption key for the BOUND is therefore **"the cell is currently over its own output-species cap"** (`after[i] > maxMass(outMat[i])`), **not** "species changed this step": a boiled steam cell stays steam for the multiple steps it takes to relax, so a species-change key would stop exempting it after step 1 and the gate would freeze the still-relaxing over-cap steam. Exempting any over-cap fluid cell from the BOUND is safe precisely because the dual-index conservation sum (never exempted) still forbids mass invention. The bound exemption needs no transition mask at all — it reads only `after[i]` against the cell's own cap.

- [ ] **Step 1: Write the failing tests** — append to `StepValidatorMassTest`.

```java
    private static final char LAVA_IX = 3;
    private static final char STEAM_IX = 4;

    private static Material lava() {
        // canonical 17-arg ctor: fluid=true, minFlow=400, maxMass=3100, gas=false
        return new Material(Identifier.fromNamespaceAndPath("orge", "lava"),
                1f, 1f, 0f, 3100f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, Float.NaN, false, true, 400f, 3100f, false);
    }

    private static Material steam() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "steam"),
                1f, 1f, 0f, 0.6f, 0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                null, null, null, Float.NaN, false, true, 0.6f, 0.6f, true);
    }

    /** VOID=0, water=1, generic_solid=2, lava=3, steam=4. */
    private static List<Material> perSpeciesLut() {
        return List.of(MaterialLut.VOID, water(), genericSolid(), lava(), steam());
    }

    @Test
    void perSpeciesAcceptsEachSpeciesConserved() {
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        // water moves 100 kg cell0->cell1; both water in AND out -> conserved per species.
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 400f; after[1] = 600f;
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void perSpeciesRejectsCrossSpeciesLeak() {
        // Total mass conserved, but water lost 100 kg and lava gained 100 kg (a mislabeled drain).
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        outMat[0] = LAVA_IX;                // cell0 is lava after the (bogus) step
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 600f; after[1] = 400f;   // lava sumAfter=600 vs sumBefore=0, water short by 100 -> fails
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void perSpeciesBoundsEachCellByItsOwnNegativeFloor() {
        // The per-cell bound on the OUTPUT species catches a NEGATIVE water cell (-200 kg): cell1 is
        // water-out and -200 < -ε, so the bound rejects regardless of the batch max (the old scalar
        // bound would also reject negatives, but the per-species bound is what guards each species'
        // OWN [−ε, max] window). The over-cap exemption is over-cap-ONLY and never relaxes the lower
        // (negative) bound, so a negative mass always fails.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        before[0] = 1000f; after[0] = 800f; after[1] = -200f; // cell1 negative -> bound fails
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void wettingAnAirCellIsConserved() {
        // REGRESSION GUARD for the wetting defect: a water cell (1000 kg) donates 125 kg into an
        // adjacent AIR cell, which adopts the water species (air matIx 0 -> water). Dual-index sum:
        // water sumBefore = donor 1000 (recipient was air -> not counted in sumBefore); water sumAfter
        // = donor 875 + recipient 125 = 1000. Conserved -> gate returns true (the OLD output-indexed +
        // species-change-exempt design rejected this and froze the spread).
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, WATER_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, WATER_IX);
        // cell0 was air, becomes water (the wetted recipient); cell1 is the donor water.
        inMat[0] = 0;            // air in
        outMat[0] = WATER_IX;    // water out (wetted)
        before[0] = 0f;          // air had no mass
        after[0] = 125f;         // received 125 kg of water
        before[1] = 1000f;       // donor full water
        after[1] = 875f;         // donor gave 125 kg
        // rest is a flat conserved water field (in==out==water, before==after)
        java.util.Arrays.fill(before, 2, 4096, 500f);
        java.util.Arrays.fill(after, 2, 4096, 500f);
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void densitySwapWithAirIsConserved() {
        // Steam below air rises (Plan-1 swap): lower cell steam(0.6) -> air(0), upper cell air(0) ->
        // steam(0.6). Dual-index: steam sumBefore = lower-in 0.6; steam sumAfter = upper-out 0.6. ✓
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; char[] outMat = new char[4096]; // all air (0) by default
        int lo = 0, up = 1;
        inMat[lo] = STEAM_IX; outMat[lo] = 0;        before[lo] = 0.6f; after[lo] = 0f;
        inMat[up] = 0;        outMat[up] = STEAM_IX;  before[up] = 0f;   after[up] = 0.6f;
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void boilVolumeOverCapCellIsExemptFromTheBoundButCountedInTheSum() {
        // A §7/engine boil left one steam cell holding 1000 kg (way over steam's 0.6 cap). It is exempt
        // from the per-cell BOUND (over its own output cap) so it does not fail on the cap, AND it is
        // still COUNTED in steam's conservation sum (never exempted from the sum). Here a neighbouring
        // water cell lost exactly 1000 kg (the boil source on the input side, mislabeled as steam? no:
        // model the realistic relaxation step where the over-cap steam stays steam in AND out and just
        // sheds mass to a steam neighbour). steam sumBefore = 1000 + 0.6, sumAfter = 999.4 + 0.6+... we
        // model the simplest conserved case: the over-cap cell sheds 0.4 to an adjacent steam cell.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, STEAM_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, STEAM_IX);
        before[0] = 1000f; after[0] = 999.6f;  // over-cap steam, sheds 0.4 (still way over the 0.6 cap)
        before[1] = 0.6f;  after[1] = 1.0f;     // neighbour steam gains 0.4 (also over the 0.6 cap)
        java.util.Arrays.fill(before, 2, 4096, 0.6f);
        java.util.Arrays.fill(after, 2, 4096, 0.6f);
        // Both cell0 and cell1 are over the 0.6 cap -> exempt from the BOUND; the conservation sum
        // (steam in == steam out, total unchanged) still passes -> gate returns true.
        assertTrue(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }

    @Test
    void overCapCellThatInventsMassStillFails() {
        // The bound exemption can NEVER hide invented mass: an over-cap steam cell that gains 1000 kg
        // from nowhere (no matching loss anywhere) is exempt from the BOUND but the conservation sum
        // (steam sumAfter exceeds sumBefore by 1000 kg, far over the ε·N ≈ 40.96 kg tolerance) rejects it.
        float[] before = new float[4096]; float[] after = new float[4096];
        char[] inMat = new char[4096]; java.util.Arrays.fill(inMat, STEAM_IX);
        char[] outMat = new char[4096]; java.util.Arrays.fill(outMat, STEAM_IX);
        before[0] = 0.6f; after[0] = 1000.6f;   // over the 0.6 cap (bound-exempt) but +1000 invented
        java.util.Arrays.fill(before, 1, 4096, 0.6f);
        java.util.Arrays.fill(after, 1, 4096, 0.6f);
        assertFalse(StepValidator.massConservedPerSpecies(after, before, inMat, outMat, perSpeciesLut()));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.StepValidatorMassTest" --rerun-tasks`
Expected: compile FAIL — `StepValidator.massConservedPerSpecies` does not exist.

- [ ] **Step 3: Add the per-species gate to `StepValidator`**

Add the new method (the single-loop, small-accumulator design). Keep the existing `massConserved` overloads untouched for back-compat:

```java
    /**
     * §9 per-species mass gate (Spec Decision 6/12, Phase-2b). One O(N) pass with a <b>dual-index</b>
     * conservation sum: each cell's {@code before} mass is accumulated under its <b>input</b> species
     * ({@code inMat[i]}) and its {@code after} mass under its <b>output</b> species ({@code outMat[i]}),
     * each only when that species is a tracked fluid (index != 0 and {@code fluid()}). After the pass,
     * every tracked fluid species must be conserved within {@code ε·N}. The conservation sum is
     * <b>never exempted</b> — this is what makes it the real gate: it conserves correctly across
     * wetting (donor water→water loses 125, recipient air→water gains 125, both under the water index →
     * water sumBefore 1000 == sumAfter 875+125), full-cell density swaps with air (steam below air: the
     * lower cell counts its input steam in sumBefore, the upper cell counts its output steam in sumAfter
     * → 0.6 == 0.6), liquid sort swaps (each species conserved on its own dual index), and drains (the
     * neighbours' after-sums under their own output species capture the donated mass), and it forbids
     * mass invention (a water→air drain mislabeled as lava fails because lava's after-sum would exceed
     * its before-sum while water's after-sum falls short).
     *
     * <p>Separately, each fluid cell is bounded on its <b>output</b> species,
     * {@code after[i] ∈ [−ε, maxMass(outMat[i]) + ε]}. The ONLY exemption is from this BOUND: a cell
     * already <b>over its own output-species cap</b> ({@code after[i] > maxMass(outMat[i])}) is a
     * transient compressed parcel — a §7/engine boil deposit (Decision 12 boil-volume) that the
     * advection pass relieves over the next steps — and skips the bound. The exemption key is
     * "over cap", NOT "species changed this step": a boiled steam cell stays steam for the multiple
     * steps it takes to relax, so a species-change key would stop exempting it after step 1 and freeze
     * the still-relaxing over-cap steam. Exempting an over-cap cell from the bound is safe precisely
     * because the dual-index conservation sum (never exempted) still prevents mass invention. Air/void
     * (index 0) and solids are not advection masses and contribute to neither sum.
     *
     * @param after   engine mass output (length N)
     * @param before  snapshot input mass (length N)
     * @param inMat   per-cell INPUT species (the snapshot {@code matIx}); index into {@code lut}
     * @param outMat  per-cell OUTPUT species (the engine's {@code material()}); index into {@code lut}
     * @param lut     batch material table (index 0 = {@link MaterialLut#VOID})
     */
    public static boolean massConservedPerSpecies(float[] after, float[] before,
                                                  char[] inMat, char[] outMat,
                                                  List<Material> lut) {
        float cellEps = MASS_EPSILON_PER_CELL;
        int speciesCount = lut.size();
        double[] sumBefore = new double[speciesCount];
        double[] sumAfter = new double[speciesCount];
        for (int i = 0; i < after.length; i++) {
            if (!Float.isFinite(after[i])) return false;
            int in = inMat[i];
            int out = outMat[i];
            // BEFORE conserved under the cell's INPUT species; AFTER under its OUTPUT species. The two
            // sums are decoupled, so a wetted air cell (air in, water out) does not count its 0 'before'
            // under water yet contributes its 'after' to water -> donor + recipient balance under water.
            if (in != 0 && lut.get(in).fluid()) {
                sumBefore[in] += before[i];
            }
            if (out != 0 && lut.get(out).fluid()) {
                float bound = lut.get(out).maxMass(); // per-species cap (canonical accessor: 0 -> defaultMass)
                // BOUND on the output species, exempting a cell already OVER its own cap (a transient
                // §7/engine boil deposit relaxing over the next steps). The conservation sum below is
                // NEVER exempted, so the exemption can hide an over-cap parcel but never invented mass.
                if (!(after[i] > bound) && (after[i] < -cellEps || after[i] > bound + cellEps)) {
                    return false;
                }
                sumAfter[out] += after[i];
            }
        }
        double tol = (double) cellEps * after.length;
        for (int s = 1; s < speciesCount; s++) {
            if (Math.abs(sumAfter[s] - sumBefore[s]) > tol) return false;
        }
        return true;
    }
```

> Cost note (Decision 6): still **one** 4096-cell loop; the only addition is **two** small `double[speciesCount]` accumulators (a handful of entries each) and a trailing `speciesCount`-length compare. No extra grid pass. The dual indexing reads `inMat[i]` and `outMat[i]` per cell — both already in hand (the snapshot `matIx` and the engine `material()`), so no extra array is built.

- [ ] **Step 4: Wire `Scheduler` to call the per-species gate**

In `Scheduler.writeBackResults`, the advection branch currently calls `StepValidator.massConserved(cleanM, entry.task().mass(), fullMassBound, entry.task().matIx(), pendingMaterials)`. Replace that call with the per-species gate, passing **both** the snapshot input species (`entry.task().matIx()`) AND the engine output species (`r.material()`) so the validator can run its dual-index sum. No transition mask is built or passed — the validator keys its (bound-only) exemption on a cell being over its own output cap, computed inside the loop from `after[i]` and `maxMass(outMat[i])`.

> **§7-vs-§9 ordering (verified against `Scheduler.writeBackResults` + `MinecraftPhaseChanger`):** in the advection branch the §9 gate runs **first**; only on a passing result does `world.writeBack` run, then (on a coincident conduction tick) `phaseChanger.applyPhaseChanges`, then `fluidReconciler.reconcile`. So §7 runs *after* the §9 gate. But `MinecraftPhaseChanger` writes only to the **world** (`level.setBlock`) and re-pins **temperature** in the §5 store — it does **not** write mass. The over-cap "boil deposit" §9 ever sees therefore does **not** come from §7 at all; it comes from the **engine's own `matOut` species-flip** carrying ~1000 kg of the boiled fluid, and it persists in the §5 store across cycles. The crucial consequence: a boiled steam cell stored at 1000 kg is re-read on the **next** snapshot as `matIx == steam`, so on every subsequent step `inMat == outMat == steam` — the cell is *no longer a transition*, yet it is still over its 0.6 cap for the several steps the gas-spread takes to relax it. A "species changed this step" exemption key would therefore exempt it only on the flip step and then reject (freeze) the still-relaxing parcel. That is exactly why both the gate's bound exemption (above) and the `cleanMass` restore (below) key on **"over its own output cap"**, not on a transition.

```java
                float[] cleanM = StepValidator.cleanMass(r.mass(), fullMassBound);
                char[] outMat = r.material();
                if (outMat != null) {
                    // Preserve any over-cap parcel (a §7/engine boil deposit) that cleanMass would
                    // otherwise clamp to fullMassBound and destroy (Decision 12 landmine). Key on the
                    // cell being over its OWN output-species cap — robust across the multi-step relaxation,
                    // not just the flip step (a boiled steam cell stays steam while it relaxes).
                    for (int c = 0; c < cleanM.length; c++) {
                        int s = outMat[c];
                        if (s != 0 && pendingMaterials.get(s).fluid()
                                && Float.isFinite(r.mass()[c]) && r.mass()[c] >= 0f
                                && r.mass()[c] > pendingMaterials.get(s).maxMass()) {
                            cleanM[c] = r.mass()[c]; // boil-volume: keep the over-cap deposit unclamped
                        }
                    }
                    if (!StepValidator.massConservedPerSpecies(cleanM, entry.task().mass(),
                            entry.task().matIx(), outMat, pendingMaterials)) {
                        LOGGER.warn("[ORGE] advection mass not conserved (per-species) for {}; holding previous mass",
                                entry.key());
                        continue;
                    }
                } else {
                    // No engine species (stub/back-compat): fall back to the total-fluid gate.
                    if (!StepValidator.massConserved(cleanM, entry.task().mass(), fullMassBound,
                            entry.task().matIx(), pendingMaterials)) {
                        LOGGER.warn("[ORGE] advection mass not conserved for {}; holding previous mass",
                                entry.key());
                        continue;
                    }
                }
                world.writeBack(entry, new StepResult(cleanT, cleanM, r.material()));
```

> Replace the existing single `if (!StepValidator.massConserved(...))` block (L280–288) and the `world.writeBack(entry, new StepResult(cleanT, cleanM));` line (L289) with the block above. The `r.material()` carried into `writeBack` is what Plan 1 Task 9 already established; if Plan 1's `writeBack` line already reads `r.material()`, keep it.
>
> **`cleanMass` over-cap restore (Decision 12 landmine):** `StepValidator.cleanMass(r.mass(), fullMassBound)` clamps every cell to `fullMassBound`, which would clamp an over-cap boil-deposit steam cell down and **destroy** the boil mass. The restore loop above (placed immediately after `float[] cleanM = StepValidator.cleanMass(...)` and before the gate) re-installs the engine's raw value on any cell **over its own output-species cap** (finite, ≥ 0). This is the same over-cap key the gate's bound exemption uses, so the parcel that the gate accepts is the parcel that gets written — and because it keys on over-cap rather than on the transition step, it keeps protecting the parcel across all the steps the engine's gas-spread (Plan 1) takes to relax it. The unclamped value is transient: each advection step relieves it.

- [ ] **Step 5: Run the new tests, the full suite, then the loader gate**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.StepValidatorMassTest" --rerun-tasks`
Expected: PASS (all new per-species cases, including the **wetting** regression guard `wettingAnAirCellIsConserved`, the density-swap-with-air case, the over-cap bound-exemption case, and the over-cap-invents-mass rejection; plus the existing scalar-gate cases still green).
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green — `SchedulerMassTest` exercises the advection writeback; confirm it still passes with the per-species gate (its conserved fixtures stay single-species water, so per-species == total there).
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile.

- [ ] **Step 6: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/StepValidatorMassTest.java
git commit -m "feat(validation): §9 per-species mass conservation (one O(N) pass) + boil-volume exemption

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 7 [MAIN]: final gate — both loaders + full suite green

**Files:** none (verification only).

- [ ] **Step 1: Full loader gate + suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava :core:test`
Expected: all green. End state of Plan 2: water **visibly** falls + spreads into air and stops finite; steam rises; water next to lava still steams/obsidians via §7; per-species §9 holds; the reconcile throttle keeps packet volume down.

- [ ] **Step 2: Confirm nothing is left uncommitted**

Run: `cd /home/claude/ORGE && git status --short`
Expected: clean (all work from Tasks 1–6 committed + pushed). If anything remains, it belongs to a task above — commit it under that task's message and `git push origin rebuild`.

> No new commit if Step 1/2 are clean. If a stray fixup is needed:
> ```bash
> cd /home/claude/ORGE
> git add -A
> git commit -m "chore: Phase-2b Java slice — final verification fixups
>
> Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
> git push origin rebuild
> ```

---

## Self-review notes (coverage vs spec)

| Spec item | Task(s) | How |
|---|---|---|
| **Decision 0** (three-mass model: `min_flow_mass ≤ default_mass ≤ max_mass`, `max_mass == default_mass` this slice, water/lava floors, gas crash-guard `min_flow_mass > 0`, air the ambient exception) | 1, 2, 3 | `Material`/`MaterialCodec` fields + canonical `maxMass()` fallback (Task 1); water/lava floors + steam gas/floor JSON, air untouched (Task 2); native LUT `minFlow`/`maxMass`/`gas` + air `fullMass=1.2` at index 0 (Task 3). |
| **Decision 0/1/2 material-data parts** (air-as-empty density label, general gas path — never special-case air by identity; steam a tracked gas) | 2, 3 | `steam` marked `gas: true` + `fluid: true` exercising the general tracked-gas path; air gets density label 1.2 via a general `AIR_DENSITY` constant, no air-identity branch in logic. |
| **Decision 6** (per-species §9 conservation as ONE O(N) pass; air untracked) | 6 | `massConservedPerSpecies` single loop with a **dual-index** sum — `before` accumulated under the cell's INPUT species, `after` under its OUTPUT species — so wetting (donor water→water + recipient air→water), full-cell swaps with air, liquid sort swaps, and drains all conserve; the conservation sum is **never exempted** (it is the real gate, so a drain mislabeled as another species fails). Index-0/solid contribute to neither sum; Scheduler passes both `entry.task().matIx()` (input) and `r.material()` (output). Regression-guarded by `wettingAnAirCellIsConserved`. |
| **Decision 7** (reconciler `air → fluid` placement incl. `orge:steam`, reads `material()`, `UPDATE_CLIENTS`-only, §7 contact whitelist, level-bucket throttle Decision 13b) | 4, 5 | `levelBucket` helper (Task 4); reconciler wets air keyed on engine species, places water/lava/steam, `isPlaceableTarget` whitelist refuses to stomp §7 solids, `bucketOfWorldBlock` throttle, `setIfChanged` keeps `UPDATE_CLIENTS` (Task 5). |
| **Decision 12** (boil-volume exemption + over-cap deposit) | 6 | the per-cell `max_mass` **BOUND** (on the OUTPUT species) exempts cells **already over their own cap** in `massConservedPerSpecies` — NOT cells that "changed species this step", because a boiled steam parcel stays steam for the several steps it takes to relax (a transition key would freeze it after step 1). The exemption is bound-only; the dual-index conservation sum (never exempted) still forbids invented mass, guarded by `overCapCellThatInventsMassStillFails`. The `Scheduler` `cleanMass` restore mirrors the same over-cap key (`r.mass()[c] > maxMass(outMat[c])`), re-installing the engine value so the deposit isn't clamped/destroyed — robust across the multi-step relaxation, since §7 itself writes no mass (only blocks + re-pinned temperature) and the over-cap steam persists in the §5 store and is re-seen as a non-transition next snapshot. |
| **Decision 13b** (reconcile throttle: write only on level-bucket crossing) | 4, 5 | `levelBucket` + `bucketOfWorldBlock` compare; same-bucket cells skip the write. |

**Type consistency with Plan 1 (verified):** `StepResult.material()` → `char[]` used everywhere (Tasks 3, 5, 6); JSON keys `min_flow_mass`/`max_mass`/`gas` (Tasks 1, 2); Java accessors `minFlowMass()`/`maxMass()`/`gas()` (all); native LUT names `minFlow`/`maxMass`/`gas` in `BatchMarshaller.Flat` (Task 3); air index-0 `fullMass = 1.2` (Task 3). The native out array `matOut` is named only in C++/JNI (Plan 1) and never in this plan's Java.

**Deferred to Plan 3 (explicitly out of scope here):**
- **Dormancy / active-set** (Decision 11): per-section per-pass settle countdown, `world.snapshot` stepping the active set rather than the whole sphere.
- **Wake hooks** (Decision 11): block-update/bucket events, source-roster changes, seam-flux wake, new-in-range — the `WakeSink` seam + `ExpectPlatform` impls.
- **Scratch-buffer pool** (Decision 13c): reusing `temp[]`/`mass[]`/`material[]` on the worker thread.

**Deferred beyond Phase-2b (spec "Out of scope"):** latent-heat energetics, full compressible-gas advection (`max_mass > default_mass` + gas-spread-to-resting), temperature-dependent densities, hydrostatic head, new liquid pairs, cross-platform native builds.

**Flagged (carried from Plan 1 / spec risk 9):** the cross-seam §9 transient becomes more frequent under wetting; the per-species gate inherits the same closed-wall-vs-real-seam-flux limitation. Not fixed in this slice — revisit in Plan 3 if it bites (batch-level Σ or engine-reported seam flux).
