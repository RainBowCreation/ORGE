# §7 Phase Change (v1) — Design Spec

**Status:** approved (brainstorm 2026-05-30) — ready for `writing-plans`.

## Goal

Implement DESIGN §7: the server-side, second-boundary pass that runs **after** the §8
scheduler writes new temperatures into `SectionData`. For every just-updated cell whose
temperature crossed its material's boiling/freezing threshold, replace the block with the
**target block**, carrying the cell's temperature across. v1 ships the headline cycles:

- **water → ice** (freeze, `< 273.15 K`) and **ice → water** (melt, `> 273.15 K`).
- **water → steam** (boil, `> 373.15 K`) and **steam → water** (condense, `< 373.15 K`).
- **lava → stone** (freeze, `< 1000 K`) — already present in seed data, now actually fired.

## Decisions (resolved in brainstorm)

1. **Hook seam:** a new `net.rainbowcreation.orge.phase` package. The MC-free pure decision
   logic is unit-testable; a thin MC adapter does the `setBlock`. The `Scheduler` calls a
   `PhaseChanger` seam after a successful write-back. (NOT inline in `writeBack`.)
2. **Mass:** **carry temperature only.** Temperature is the simulated authority and lives in
   `SectionData` untied to block identity, so it carries automatically (we never touch it on a
   swap). Mass conservation is **deferred to Phase 2** (§8 derives per-cell mass from
   `Material.defaultMass`; mass is not authoritative yet). No mass read/write in §7.
3. **New blocks — the rule:** core registers a **new block only for a genuinely new game
   concept** (gases/fluids that vanilla lacks). Everything vanilla already has (ice, stone) is
   an **override**, never re-created. So §7 registers exactly **one** block, `orge:steam`
   (the gas concept); water-freeze/lava-freeze targets are the **vanilla** `minecraft:ice` /
   `minecraft:stone` blocks.
4. **Target semantics:** `boiling_target` / `freezing_target` are the **block id to place**
   (matches the existing seed data: `lava.freezing_target = minecraft:stone`,
   `water.freezing_target = minecraft:ice`). The pass places that block directly; the placed
   block's own thermal behaviour and reverse transition come from **its** binding/material.
   `Material.representativeBlock` is **not consulted** by the v1 phase pass (kept for §6/future).
5. **Oscillation:** strict inequalities (`>` boil, `<` freeze) plus **distinct target
   materials** make the cycle stable. Water boils at `T > 373.15`; steam condenses at
   `T < 373.15`; exactly `373.15` is a no-op for both. No hysteresis guard needed.
6. **Geometry invalidation:** none required. §8 rescans geometry every snapshot, so a §7 block
   swap is picked up next tick with the new block's material automatically.
7. **`setBlock` flag:** `Block.UPDATE_CLIENTS` only — push the change to clients, but **no**
   neighbour shape/physics cascade. Server-thread only.

## Performance principle for gas/liquid blocks (architecture, mostly Phase 2)

`orge:steam` (and future fluid blocks) are **dumb 1 Hz-driven markers**. All simulation state
stays in the existing per-cell `float[4096]` arrays in `SectionData`; the block carries **no**
simulation data.

- **No simulation data in blockstate/NBT** — temperature and (Phase-2) mass/fluid-level live in
  the `SectionData` arrays. Blockstate/NBT churn is the dominant fluid cost; avoid it.
- **No vanilla-style ticking** — `orge:steam` has **no** `randomTick`, **no** `scheduledTick`,
  **no** `BlockEntity`. The ORGE scheduler is the sole driver. (Removing per-block fluid ticking
  is the core perf win over vanilla water/lava.)
- **Phase 2 flow** runs natively in the C++ engine as a mass-redistribution pass over the same
  `mass[]` arrays conduction already loads zero-copy via JNI; Java reconciles only cells whose
  mass crossed a discrete level into batched `setBlock(UPDATE_CLIENTS)` calls.
- **Phase 2 render level** is a small blockstate int derived from cell mass at reconcile time —
  cheap discrete rendering over a continuous sim.

**Out of scope for §7 (Phase 2 / DESIGN §10):** gas/liquid flow, viscosity, steam buoyancy,
discrete fluid levels, mass conservation. §7 ships steam as a single **inert** block.

**Known gap — vanilla ice/snow freezing is a competing authority (follow-on).** §7 freezes
water → ice (and melts back) by the **simulated** cell temperature, but vanilla *also* freezes
water → ice and forms/melts snow on its own model (biome temperature, sky exposure, block
light > 11; packed/blue ice are light-immune). The two authorities will disagree in-game (e.g.
vanilla freezes water ORGE considers warm, or ORGE freezes water in a "warm" biome). The
intended verdict is **SUPERSEDE**: ORGE's simulated temperature wins, so a follow-on must
**suppress vanilla ice/snow freeze+melt** (analogous to Phase-2a's `VanillaFluidSuppressor`,
same mixin caveat) and let §7 drive these transitions. Integration bonus: torches/lanterns are
already ORGE heat sources (engine-audit B+C), so "ice melts near a torch" emerges from the sim,
strictly better than vanilla's `light > 11` rule. (Full registry of vanilla-thermal verdicts
lives in the Phase-2a spec's "Vanilla thermal interactions" section.)

## Components

All new files under `core/src/main/java/net/rainbowcreation/orge/phase/` unless noted.

### Pure units (MC-free, unit-tested)

- **`PhaseRule`** — the decision, no state.
  ```java
  // Boiling checked first. Strict inequalities; ±∞/null defaults ⇒ Optional.empty().
  static Optional<Identifier> targetBlock(float temperatureK, Material current);
  ```
  - `temperatureK > current.boilingPoint() && current.boilingTarget() != null` → `boilingTarget`.
  - else `temperatureK < current.freezingPoint() && current.freezingTarget() != null` → `freezingTarget`.
  - else empty.

- **`PhasePlanner`** — one section's plan, no MC types beyond `Identifier`.
  ```java
  record Transition(int cellIndex, Identifier blockId) {}
  // cellMaterial: cell 0..4095 (x+16y+256z) → its current Material.
  // blockExists:  target block id is registered (injected; MC-resolved in production).
  static List<Transition> plan(float[] temperatures,
                               IntFunction<Material> cellMaterial,
                               Predicate<Identifier> blockExists);
  ```
  - For each of the `SectionData.CELLS` cells: `PhaseRule.targetBlock(temps[i], cellMaterial.apply(i))`;
    if present and `blockExists` and the target differs from the cell's current block-material's
    own block, record a `Transition`. (A cell already showing the target needs no work — but the
    planner only sees materials, so the cheap guard is "skip if `blockExists` is false"; placing a
    block equal to the existing one is harmless and rare. Keep the planner simple: emit a
    transition whenever the rule fires and the target exists.)

### Seam + MC adapter

- **`PhaseChanger`** (interface) — what the `Scheduler` depends on (stays MC-free):
  ```java
  void applyPhaseChanges(ThermalWorld.BatchEntry entry);
  PhaseChanger NOOP = entry -> {};
  ```

- **`MinecraftPhaseChanger`** (the only MC-coupled new class) — mirrors
  `MinecraftThermalWorld`'s server-binding pattern (`bindServer`/`unbindServer`, `volatile
  MinecraftServer`). For one `BatchEntry`:
  1. Resolve the `ServerLevel` for `entry.dimension()`; bail if null/unbound.
  2. Resolve the loaded `LevelChunk` + `LevelChunkSection` for `entry.key()`; **skip if it
     unloaded** since the snapshot (reuse `MinecraftThermalWorld`'s `getChunkNow`/section helpers
     — extract shared helpers or duplicate the tiny static methods).
  3. Read the post-write temps from `SectionStore.get(key)` (`temperatureAt(i)`).
  4. Build the per-cell `Material` via the existing `LIVE_TAGS` + `ActiveMaterials.current()`
     bridge (the cell's current block → material). Reuse the `materialFor(Block, State)` logic
     from `MinecraftThermalWorld` (extract to a shared helper, e.g. `LiveMaterials`, to avoid
     copy-paste).
  5. `PhasePlanner.plan(temps, cellMaterial, id -> BuiltInRegistries.BLOCK.containsKey(id))`.
  6. For each `Transition`: `level.setBlock(blockPosOf(key, cellIndex),
     BuiltInRegistries.BLOCK.getValue(blockId).defaultBlockState(), Block.UPDATE_CLIENTS)`.
     `blockPosOf`: section-origin (`key.cx()<<4`, `key.sectionY()<<4`, `key.cz()<<4`) plus the
     cell's local `(x,y,z)` decoded from `x+16y+256z`. **Temperature is left untouched in
     `SectionData`** → it carries across the swap.

> **Shared MC helpers.** `MinecraftThermalWorld` already has `materialFor`, `LIVE_TAGS`,
> `loadedChunk`, `sectionOrNull`, `blockAt`. To avoid duplication, extract the reusable pieces
> (the live material lookup + chunk/section resolution) into a small package-visible helper that
> both `MinecraftThermalWorld` and `MinecraftPhaseChanger` use. Keep the refactor minimal and
> behaviour-preserving (covered by the existing §8 scheduler integration test).

### Wiring

- **`Scheduler`** — add a `PhaseChanger` constructor parameter (5th). In `complete(...)`, after
  the write-back loop, for each of the `n` entries actually written call
  `phaseChanger.applyPhaseChanges(pendingEntries.get(i))`. (Same `n = min(results, entries)`
  bound; only written entries get a phase pass.) A write-back exception path already returns
  early, so phase changes only run for cleanly-written results. Existing tests pass
  `PhaseChanger.NOOP`.
- **`Orge.init`** — construct `MinecraftPhaseChanger(SECTION_STORES)`, bind it on the existing
  `SERVER_STARTED` listener and unbind on `SERVER_STOPPING` (beside `thermalWorld`), and pass it
  into the `Scheduler` constructor.

### Block registration (`orge:steam`)

Architectury **common** `DeferredRegister<Block>` (confirm exact API with `javap` against the
architectury jar — the project has repeatedly found skill examples stale). First block in the
project, so this also establishes the registration pattern.

- **Block:** a plain `Block` (or minimal subclass) with air-like
  `BlockBehaviour.Properties`: `noCollission()`, `replaceable()`, `noOcclusion()`/not solid,
  `instabreak()`, `mapColor(MapColor.NONE)`, no loot. **No `BlockItem`** (only ever spawned by
  phase change). **No** `randomTick`/`BlockEntity`/scheduled tick.
- **Registration class:** `core/.../block/ModBlocks.java` (common Architectury
  `DeferredRegister.create(MOD_ID, Registries.BLOCK)` + `register("steam", …)`), called from
  `Orge.init()`. If any piece genuinely needs `@ExpectPlatform`, follow the §5
  `SectionStorePlatform` pattern; prefer the common `DeferredRegister` (usually sufficient).
- **Assets** (hand-authored — `runData`/`runClient` don't run in this headless sandbox; verify by
  `build`): `assets/orge/blockstates/steam.json`, `assets/orge/models/block/steam.json` (a simple
  translucent cube referencing an existing vanilla texture, or `RenderShape` minimal), and an
  `assets/orge/lang/en_us.json` entry `block.orge.steam = "Steam"`. Asset location: the `core`
  module's resources (shared by both loaders via Architectury), consistent with where material
  JSON lives. No item model (no item).

### Datapack edits (`core/src/main/resources/data/orge/orge/`)

JSON field names per `MaterialCodec`: `thermal_conductivity`, `heat_capacity`, `default_mass`,
`molar_mass`, `boiling_point`, `freezing_point`, `boiling_target`, `freezing_target`,
`representative_block`.

- **`materials/steam.json`** (`orge:steam`): gas constants —
  `thermal_conductivity ≈ 0.025`, `heat_capacity ≈ 2080`, `default_mass ≈ 0.6`,
  `molar_mass 0.018`, `freezing_point 373.15`, `freezing_target "minecraft:water"`
  (condenses back to water), `representative_block "orge:steam"`.
- **`materials/ice.json`** (`orge:ice`): `thermal_conductivity ≈ 2.2`, `heat_capacity ≈ 2050`,
  `default_mass ≈ 917`, `molar_mass 0.018`, `boiling_point 273.15`,
  `boiling_target "minecraft:water"` (melts to water), `representative_block "minecraft:ice"`.
- **`materials/water.json`**: **no change needed** — seed already has `boiling_target "orge:steam"`
  and `freezing_target "minecraft:ice"`, both correct under this model. (Confirm during
  implementation; only edit if it has drifted.)
- **`bindings/default.json`**: add overrides `"orge:steam": "orge:steam"` and
  `"minecraft:ice": "orge:ice"`. (`minecraft:stone` already resolves via the `c:stones` tag →
  `generic_solid`; cooled lava staying stone is fine.)

## Testing

- **`PhaseRuleTest`** (pure): boil above threshold, freeze below, no-op in band, no-op at exact
  threshold, null `boilingTarget`/`freezingTarget` → empty, `±∞` defaults → empty, boiling
  precedence when both could fire.
- **`PhasePlannerTest`** (pure, fakes): uniform section all-transition; heterogeneous per-cell
  transitions; target with `blockExists == false` skipped; cell index/blockId correctness.
- **`SchedulerTest`** (extend): a recording `PhaseChanger` fake asserts `applyPhaseChanges` is
  invoked once per written entry after a successful step, and **not** invoked on the
  failed-step/late-cancel paths. `NOOP` keeps all existing scheduler assertions green.
- **Integration (optional, server-thread):** extend the existing scheduler+engine integration
  test or add a focused one driving a hot water cell to `>373.15 K` and asserting the planner
  produces a `steam` transition (kept at the pure-planner level since `setBlock` needs a live
  level not available in `:core` unit tests).
- **Build gate:** `:core:test` (existing 148 + new) green, and full both-loader `build` (both
  remapped jars, `liborge.so` bundled, `orge:steam` registered, assets present).

## File summary

**New (core):**
`phase/PhaseRule.java`, `phase/PhasePlanner.java`, `phase/PhaseChanger.java`,
`phase/MinecraftPhaseChanger.java`, `block/ModBlocks.java`,
(shared helper for live material lookup, e.g. `scheduler/LiveMaterials.java` or `material/…`).
Tests: `phase/PhaseRuleTest.java`, `phase/PhasePlannerTest.java`.

**Modified (core):**
`scheduler/Scheduler.java` (+`PhaseChanger` param + post-write call),
`scheduler/MinecraftThermalWorld.java` (extract shared helper),
`scheduler/SchedulerTest.java` (recording fake), `Orge.java` (construct/bind/wire),
`material/MaterialJsonLoader.java` (remove the stale "phase-change deferred" TODO comment).

**New/edited resources (core):**
`data/orge/orge/materials/{steam,ice}.json`, `data/orge/orge/bindings/default.json`,
`assets/orge/blockstates/steam.json`, `assets/orge/models/block/steam.json`,
`assets/orge/lang/en_us.json`.

**Docs:** `DESIGN.md` §7 clarified (new-block-only-for-new-concepts rule; gases new, solids
override); add an `orge-phase-change.md` memory entry + MEMORY.md pointer after the track.

## Build / test environment

```
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH \
  GRADLE_USER_HOME=/home/claude/.gradle ./gradlew <tasks>
```
Fast loop `:core:test`; single class `:core:test --tests '...PhaseRuleTest'`; full both-loader
gate `build`. Headless/no-GL: `runClient`/`runData` do **not** run here — verify assets by
`build`, reason about JSON. Confirm 1.21.11 APIs (`BlockBehaviour.Properties`, `Blocks`,
architectury `DeferredRegister`, `Level.setBlock(BlockPos, BlockState, int)`, `BlockPos`,
`SectionPos`) with `javap` over the loom-mapped MC jar, not the skill examples.

## Conventions

Branch `rebuild`, work directly (no worktree). One commit per task, push `origin rebuild` after
each; trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. No PR / no
merge to main while v1 is incomplete. `:core` purity: no `net.fabricmc.*`/`net.neoforged.*`
(`net.minecraft.*` allowed).
