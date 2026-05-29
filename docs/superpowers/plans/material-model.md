# Implementation Plan — Material Model (DESIGN.md §6)

Phase-1 thermal-core subsystem #1. Builds the **data-driven material model**: the
constant property set per material, the active registry, block→material bindings, and
the datapack JSON loader that populates them, reloadable via `/reload`.

This plan is executed subagent-driven (fresh implementer per task + spec review + code
quality review). Derived from `DESIGN.md §6` (authoritative). Material identity is
**never** stored per cell — it's derived from the block via bindings.

## Ground rules for every task

- **Repo:** `/home/claude/ORGE`, branch `rebuild`. Mojang mappings, MC 1.21.11, Java 21.
- **Build/test command (no `java` on PATH — use this exact env):**
  ```
  JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH \
    GRADLE_USER_HOME=/home/claude/.gradle ./gradlew <tasks>
  ```
- **1.21.11 gotcha:** the namespaced-id class is `net.minecraft.resources.Identifier`
  (NOT `ResourceLocation`); factory `Identifier.fromNamespaceAndPath(ns, path)`. It has
  `public static final Codec<Identifier> CODEC`.
- **Serialization:** use Mojang's `com.mojang.serialization.Codec` + `JsonOps.INSTANCE`
  (DFU, already on the classpath via Minecraft). JSON via Gson `JsonElement`.
- **TDD:** write the failing test first, then implement. Tests live in
  `core/src/test/java/...`. Run `:core:test` green before committing. Commit per task.
- **Scope discipline:** build only what the task says. Targets/blocks that don't exist
  yet (e.g. `orge:steam`) are just `Identifier` values in data — do NOT create blocks.

## File layout (within `:core`, package `net.rainbowcreation.orge.material`)

```
material/
  Material.java          (exists — the record)
  MaterialCodec.java     (T2 — Codec<Material> + field defaults)
  MaterialRegistry.java  (exists — flesh out + test in T3)
  MaterialBindings.java  (exists — implement resolution in T4)
  MaterialData.java      (T5 — pure parse functions JSON -> registry/bindings)
  MaterialJsonLoader.java(exists — T6 wires the reload listener)
core/src/main/resources/data/orge/orge/materials/{air,water,lava,generic_solid}.json (T5)
core/src/main/resources/data/orge/orge/bindings/default.json                           (T5)
```

---

## Task 1 — Test infrastructure for `:core`

**Goal:** make `:core` unit-testable with JUnit 5, with Minecraft classes available on
the test classpath.

- In `core/build.gradle` add JUnit 5 (`org.junit.jupiter:junit-jupiter:5.11.3` via
  `testImplementation`, plus `testRuntimeOnly` launcher as needed) and
  `test { useJUnitPlatform() }`.
- Add one sample test `core/src/test/java/net/rainbowcreation/orge/SmokeTest.java` that
  imports `net.minecraft.resources.Identifier` and asserts
  `Identifier.fromNamespaceAndPath("orge","x").toString()` equals `"orge:x"`. This proves
  both JUnit **and** the Mojang-mapped Minecraft classpath work in tests.
- **Verify:** `./gradlew :core:test` is green. If Minecraft isn't on the test classpath,
  fix the loom/test config so it is (this is the whole point of the task) — if it needs
  a loom option you're unsure about, report DONE_WITH_CONCERNS describing what you found.
- Commit.

**Acceptance:** `:core:test` runs and the smoke test passes.

---

## Task 2 — `MaterialCodec`: Codec<Material> + defaults

**Goal:** serialize/deserialize the `Material` record from JSON with sane defaults.

- Create `MaterialCodec` exposing `public static final Codec<Material> CODEC`.
- JSON keys are **snake_case**: `thermal_conductivity`, `heat_capacity`, `viscosity`,
  `default_mass`, `molar_mass`, `boiling_point`, `freezing_point`, `boiling_target`,
  `freezing_target`, `representative_block`. The material's own `id` is supplied by the
  loader (the file name), so the codec does NOT read `id` from the body — provide a codec
  shape that takes the 10 body fields, and a small helper
  `Material fromJson(Identifier id, JsonElement body)` (or a `Codec` built so the loader
  supplies id). Pick the cleaner of the two; document the choice.
- **Optional fields with defaults:** `viscosity` default `0`, `molar_mass` default `0`,
  `boiling_point` default `Float.POSITIVE_INFINITY`, `freezing_point` default
  `Float.NEGATIVE_INFINITY` (i.e. "never transitions" unless specified). `boiling_target`,
  `freezing_target`, `representative_block` are **optional/nullable** (absent ⇒ null ⇒
  no transition / not placeable). `thermal_conductivity`, `heat_capacity`, `default_mass`
  are **required**.
- **TDD tests** (`MaterialCodecTest`): (a) full object round-trips; (b) a minimal object
  (only the 3 required fields) decodes with the documented defaults and null targets;
  (c) a "gas-like" object with a `boiling_target` but no `freezing_target` decodes
  correctly. Use `CODEC ... JsonOps.INSTANCE`.
- Commit.

**Acceptance:** all `MaterialCodecTest` cases pass; defaults exactly as specified.

---

## Task 3 — `MaterialRegistry` behavior + tests

**Goal:** finalize the active material set with a guaranteed fallback.

- The class exists. Ensure: `put(Material)`, `Optional<Material> get(Identifier)`,
  `Material getOrFallback(Identifier)` (returns the `FALLBACK_ID` material when the id is
  absent; throws `IllegalStateException` if even the fallback isn't registered),
  `Collection<Material> all()`, `clear()`. Keep it loader-agnostic (no Minecraft world
  access).
- **TDD tests** (`MaterialRegistryTest`): put/get; `getOrFallback` returns fallback for
  unknown id once a fallback is registered; `getOrFallback` throws when no fallback
  present; `clear` empties it.
- Do NOT hardcode material constants here (those are data — Task 5).
- Commit.

**Acceptance:** `MaterialRegistryTest` passes; fallback semantics exact.

---

## Task 4 — `MaterialBindings`: block→material resolution

**Goal:** resolve a block id to a material id with precedence override → tag → fallback.

- Implement `MaterialBindings`:
  - `void addOverride(Identifier blockId, Identifier materialId)`
  - `void addTagBinding(Identifier tagId, Identifier materialId)` — preserves insertion
    order as priority (first match wins among tags).
  - `Identifier materialFor(Identifier blockId, TagMembership tags)` where
    `TagMembership` is a small functional interface
    `boolean contains(Identifier tagId, Identifier blockId)` injected by the caller. This
    keeps resolution **pure and unit-testable** without the Minecraft tag system; the
    real registry-backed implementation is supplied later (Task 6).
  - Resolution: exact override → first tag binding whose tag contains the block →
    `MaterialRegistry.FALLBACK_ID`.
  - `void clear()`.
- **TDD tests** (`MaterialBindingsTest`) with a fake `TagMembership`: override wins over
  tag; tag wins over fallback; first-registered tag wins when multiple match; fallback
  when nothing matches.
- Commit.

**Acceptance:** `MaterialBindingsTest` passes; precedence exact.

---

## Task 5 — `MaterialData`: pure JSON parsing + default data files

**Goal:** turn datapack JSON into a populated registry + bindings, with the mod's own
default data validated through that same path.

- Create `MaterialData` with pure static functions (no `ResourceManager`):
  - `void loadMaterials(Map<Identifier, JsonElement> files, MaterialRegistry into)` —
    each entry's key is the material id (namespace+path from `materials/<path>.json`),
    decoded via `MaterialCodec`.
  - `void loadBindings(List<JsonElement> files, MaterialBindings into)` — bindings JSON
    shape:
    ```json
    { "tags":     [ { "tag": "c:stones", "material": "orge:generic_solid" } ],
      "overrides": { "minecraft:iron_block": "orge:iron" } }
    ```
    Parse `tags` in array order (priority) and `overrides` map; tolerate missing keys.
- **Default data resources** under `core/src/main/resources/data/orge/orge/`:
  - `materials/air.json`, `materials/water.json`, `materials/lava.json`,
    `materials/generic_solid.json`. Use reasonable real-world constants (units per
    DESIGN §6). Reference values (tune later — mechanism matters more than exactness):
    | material | thermal_conductivity | heat_capacity | default_mass | molar_mass |
    |---|---|---|---|---|
    | air | 0.026 | 1005 | 1.2 | 0.029 |
    | water | 0.6 | 4186 | 1000 | 0.018 |
    | lava | 1.5 | 1450 | 3100 | 0.060 |
    | generic_solid | 2.0 | 840 | 2500 | 0.060 |
    Water: `freezing_point` 273.15 with `freezing_target` `minecraft:ice`,
    `boiling_point` 373.15 with `boiling_target` `orge:steam` (a data value only — no
    block is created), `representative_block` `minecraft:water`. Air/generic_solid: no
    targets. Lava: `freezing_point` ~1000 with `freezing_target` `minecraft:stone`,
    `representative_block` `minecraft:lava`.
  - `bindings/default.json`: at least map air→orge:air, water→orge:water, lava→orge:lava
    via overrides, plus one example tag binding (e.g. `#minecraft:stone_buttons` or a
    common `c:` tag) → `orge:generic_solid`. Keep it minimal but representative.
- **TDD tests** (`MaterialDataTest`): (a) `loadMaterials` over an in-memory 2-entry map
  populates the registry with correct ids/values; (b) `loadBindings` parses tags+overrides
  in order; (c) a test that reads the **actual default resource files** from the test
  classpath, runs them through `loadMaterials`/`loadBindings`, and asserts air/water/lava/
  generic_solid are present and water has the freezing/boiling targets. (Use
  `getClass().getResourceAsStream("/data/orge/orge/materials/water.json")` etc.)
- Commit.

**Acceptance:** `MaterialDataTest` passes including the real-resource test; default JSON
parses cleanly.

---

## Task 6 — Runtime wiring (reload listener on both loaders)

**Goal:** make materials actually load in-game and reload on `/reload`.

- Implement `MaterialJsonLoader` as a server-data reload listener that scans
  `data/<ns>/orge/materials/` and `data/<ns>/orge/bindings/`, builds fresh
  `MaterialRegistry` + `MaterialBindings` via `MaterialData`, and swaps them in
  atomically. Use Architectury's reload-listener registration
  (`dev.architectury.registry.ReloadListenerRegistry`, `PackType.SERVER_DATA`) so one
  registration covers both loaders; if that API isn't available in architectury 19, fall
  back to `@ExpectPlatform` with Fabric `ResourceManagerHelper` + NeoForge
  `AddReloadListenerEvent` impls.
- Provide the **real** `TagMembership` for `materialFor` backed by the server's block
  registry/tags (look up `TagKey<Block>` and test membership) — used at phase-change /
  binding time. Keep the binding store and this bridge separate from the pure resolution
  logic from Task 4.
- Call the registration from `Orge.init()` (or a server-start hook as appropriate).
- **Verify:** `./gradlew build` is green on BOTH loaders. A full in-game `/reload` test
  isn't automatable here; instead assert via build + a focused integration check if
  feasible (e.g. constructing the listener and feeding it a fake resource map). This task
  is integration-heavy — if loader API specifics are uncertain, report findings rather
  than guessing.
- Commit.

**Acceptance:** both jars build; the listener is registered on both loaders; materials/
bindings load through the §6 pipeline. Note any non-unit-testable gaps honestly.

---

## After all tasks

Final whole-subsystem code review, then `superpowers:finishing-a-development-branch`.
Next subsystem (per the v1 dependency map): section store (§5).
