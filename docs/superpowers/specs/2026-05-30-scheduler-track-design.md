> **SUPERSEDED re: material LUT** — see `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`.
> `matIx` ids are now globally STABLE (fixed per material at load/`/reload`, slot 0 = VACUUM, slots 1..N
> = `MaterialRegistry.all()` sorted by namespaced id). The LUT is engine-resident (register-once via
> `orgeRegisterMaterials`), NOT shipped per `orgeStepWorld` call. Passages below describing a per-step /
> batch-local / first-seen LUT are historical.

# Scheduler Track (DESIGN.md §8) — Single-Node Async Conduction Scheduler (v1)

**Date:** 2026-05-30
**Status:** Approved design; implementation plan to follow.
**Branch:** `rebuild`. No submodule changes expected (Java/`:core` only).

## Goal

Stand up DESIGN §8 — the per-tick server orchestration that ties together the now-complete
§5 `SectionStore`, §6 material model, and §2 `OrgeEngine`. Each real second the scheduler
gathers the loaded sections around players, assembles them into `StepTask`s (geometry +
temperatures + neighbour halo), runs **one** `engine.step(...)`, validates the results, and
writes the new temperatures back into the `SectionStore`. This is the producer that makes
heat actually conduct in-game.

Scope is the **single-node server-side slice**: the server computes everything itself with
one background "fallback" engine instance (DESIGN §3). There are **no client workers, no
wire protocol, and no networking** in v1 — that distribution layer (§3 topology +
`ASSIGN`/`GEOMETRY`/`STEP_INPUT`/`STEP_RESULT`/`HEALTH`) is a deliberate follow-on track.
Phase change (§7) and Phase-2 mass simulation are also out of scope.

## Decision: single-node first (refines DESIGN §3/§8 scope for v1)

DESIGN §3 describes a server-orchestrated worker pool where every connected client is a
compute worker and the server keeps **one idle fallback engine** for worker-less regions.
v1 implements only that fallback path: the server is the sole worker. This keeps the entire
track in `:core` plus one common tick hook, makes it fully testable in the headless sandbox
(no `runClient`), and proves the geometry/halo/write-back pipeline end-to-end before the
networking layer is built on top. The multi-worker distribution is additive on top of this
seam and does not require reworking it.

## Decision: async + deadline (DESIGN §3 "server ~0 per-tick cost", §8 step 5)

The conduction step runs on a **single background thread**, not inline on the server tick
thread, so the server never stalls for the compute. Because the `SectionStore` is
server-thread-confined with no locking (§5), the background thread operates only on
**immutable snapshots** (`StepTask` arrays copied on the server thread); all `SectionStore`
and world access stays on the server thread. A step that misses the 1 s deadline **holds
the previous temperatures** for that tick (no recompute storm), and `engine.lastStepMillis()`
drives a single-worker health throttle.

## Architecture

All new code lives in `:core` package `net.rainbowcreation.orge.scheduler`. Pure logic is
isolated behind injected functional seams (the §5 `AmbientProvider` / §6 `TagMembership`
style) so it is unit-testable without a running server. Only a thin glue layer touches
`net.minecraft.*`.

### Pure units (no Minecraft imports — unit-tested with fakes)

- **`SphereUnion`** — given player anchor `SubchunkKey`s + a range N, produce the
  deduplicated set of section keys within the 3D sphere of radius N (DESIGN §4; count
  ≈ `(4/3)πr³`). Range 1 = the anchor section only.
- **`MaterialLut`** — builds a per-batch `List<Material>` and assigns stable `char`
  indices. **Index 0 = void/inert** (a sentinel material with `thermalConductivity = 0`),
  matching §2's tests and the engine's `k ≤ 0` skip. Real materials get indices ≥ 1.
  Append-on-first-encounter; rebuilt fresh each step (geometry is rescanned each step, so
  no cross-tick index stability is required).
- **`GeometryAssembler`** — over the 4096 cells of a section, via an injected
  `CellMaterials` seam (`cellIndex → Material`), produce `char[4096] matIx` (LUT index per
  cell) + `float[4096] mass` (`mass[i] = material.defaultMass()`), registering each material
  into the `MaterialLut`. Pure.
- **`HaloAssembler`** — build a `NeighborHalo` (6 temperature faces + 6 matIx faces) from
  injected neighbour-temperature and neighbour-matIx seams. Cells beyond a loaded/world edge
  are **void** (temperature irrelevant, matIx = 0). Honours the §2 conventions exactly: face
  order `negX, posX, negY, posY, negZ, posZ`; face-cell index X:`y+16z`, Y:`x+16z`,
  Z:`x+16y`; `sidx(x,y,z) = x + 16y + 256z`.
- **`StepValidator`** (DESIGN §9 trust model) — per cell: reject `NaN`/`±Inf` (keep the
  section's current value), clamp temperature to `[0, 6000]` K. The "assigned?" check from §9
  is moot single-node (no remote results to discard).
- **`ServerWorker`** (extends the existing `Worker` stub) — the single server worker's range
  + health-throttle state. Drop range by 1 (to `MIN_RANGE`) on a missed deadline or when
  `lastStepMillis()` exceeds `COMPUTE_BUDGET_MILLIS`; climb by 1 (to `MAX_RANGE`) after
  `ON_TIME_TICKS_TO_CLIMB` consecutive on-time, under-budget steps.

### Minecraft glue (`:core`, vanilla `net.minecraft.*` only — no loader imports)

- **`WorldThermalSource`** — implements the seams against the live `MinecraftServer` /
  `ServerLevel`: loaded-section enumeration, a section's `BlockState`s
  (`ChunkSection.getBlockState` → `Block` → `Material` via `MaterialBindings` + a live
  `TagMembership`), player anchor sections, and forced-chunk sections.
- **`ConductionScheduler`** — the orchestrator. Owns the single-thread background
  `ExecutorService`, the in-flight batch + deadline bookkeeping, the `ServerWorker`, and the
  engine from `EngineFactory.create()`. Drives the per-tick state machine (below). Operates
  across all loaded levels of one server (one fallback engine total, per DESIGN §3).
  Because `SubchunkKey` carries no dimension, each batch entry is tagged with its owning
  level (`ResourceKey<Level>` / dimension `Identifier`) alongside the key, so the single
  engine batch can span dimensions without `(0,0,0)` colliding: tasks are flattened into one
  `engine.step` call, but write-back routes each result to the correct dimension's
  `SectionStore`, and **halo neighbour lookups are scoped to the task's own dimension**
  (heat never conducts across a dimension boundary).
- **Tick wiring in `Orge.init()`** — register `TickEvent.SERVER_POST` (a **common**
  Architectury event, both loaders — verified via `javap`); count ticks and every 20
  (`Scheduler.TICKS_PER_STEP`) advance the scheduler. No `@ExpectPlatform` seam is needed.

### Live `TagMembership` bridge (wires the §6-deferred TODO)

`MaterialBindings.materialFor(block)` needs tag membership. v1 supplies the live bridge §6
was waiting for: `block → BuiltInRegistries.BLOCK.wrapAsHolder(block).is(TagKey.create(Registries.BLOCK, tagId))`.

### `:core` purity

No `net.fabricmc.*` / `net.neoforged.*` anywhere in this track. `net.minecraft.*` is allowed
in `:core` (as `Orge.java`/§5 already use it). The tick driver uses the Architectury **common**
`TickEvent`, so unlike §5's chunk hooks no platform seam is required.

## Data flow (the `ConductionScheduler` state machine)

Driven by `TickEvent.SERVER_POST`. At most **one** batch is in flight at a time.

- **IDLE** — each tick increments a counter. When it reaches `TICKS_PER_STEP` (20) **and** no
  batch is in flight → SNAPSHOT.
- **SNAPSHOT** (server thread, one tick — all world reads happen here):
  1. For each loaded level, build the union: each online player's anchor section ± current
     `ServerWorker.range` via `SphereUnion`, plus that level's forced-chunk sections; dedup to
     a `Set<SubchunkKey>` per level (single owner ⇒ natural dedup, DESIGN §8 step 2). Tag each
     surviving section with its dimension for write-back/halo routing.
  2. Drop sections whose chunk is not loaded (skipped this step).
  3. For each surviving section assemble a `StepTask`: `matIx` + `mass` from
     `GeometryAssembler` (rescan the live `ChunkSection`); `temperature` = a **copy** of
     `SectionStore.get(key).temperatureArray()`; `halo` from `HaloAssembler`.
  4. Submit `(tasks, lut)` to the background executor → `Future<List<float[]>>`. Record the
     deadline = current step + 1 step window. → AWAITING.
- **AWAITING** (server thread, subsequent ticks):
  - `Future` done → WRITE-BACK → IDLE (reset counter).
  - Past the deadline and not done → **hold previous temps** (leave `SectionStore`
    untouched), `ServerWorker.reportLate()`. Allow **one grace step window**, then cancel and
    drop the batch so a permanently-slow engine cannot wedge the scheduler.
- **WRITE-BACK** (server thread): for each result `float[4096]` paired with its `SubchunkKey`:
  1. `StepValidator` cleans the array (drop non-finite → keep current; clamp `[0,6000]`).
  2. Write into the section's `SectionData` and `SectionStore.put(key, data)` to mark the
     column dirty so §5 persists it. UNIFORM→FULL promotion happens through `setTemperature`;
     a result whose cells are all equal stays/collapses to UNIFORM.
  3. Feed `engine.lastStepMillis()` to `ServerWorker` (climb/drop range per the throttle).

**Threading invariant:** the background thread touches only the immutable `StepTask` arrays
and the engine. Every `SectionStore`/world read or write is on the server thread. No locks.

## Configuration (v1 constants; datapack/config wiring deferred)

| constant | value | meaning |
|---|---|---|
| `TICKS_PER_STEP` | 20 | one step per real second (existing) |
| `STEP_DT_SECONDS` | 1.0 | dt passed to `engine.step` (existing) |
| `MIN_RANGE` | 1 | range floor (existing) |
| `DEFAULT_RANGE` | 2 | initial server range |
| `MAX_RANGE` | 4 | range ceiling |
| `COMPUTE_BUDGET_MILLIS` | 250.0 | over → drop range |
| `ON_TIME_TICKS_TO_CLIMB` (K) | 5 | on-time, under-budget steps before climbing |
| deadline grace | 1 extra step window | before cancel-and-drop |

## Error handling

- **Background-step failure** (exception / engine error) is caught when the `Future`
  resolves: hold previous temps for that batch, log via `Orge.LOGGER`, return to IDLE. One
  bad step never corrupts the store.
- **Non-finite / out-of-range results** are sanitised by `StepValidator` before any write
  (DESIGN §9).
- **A section that unloaded between SNAPSHOT and WRITE-BACK** is skipped on write-back
  (`SectionStore` no longer has the column loaded) — no resurrection of unloaded data.
- **`UncheckedIOException`** from the store propagates (crash-loud, not corrupt-silent — §5
  convention).

## Testing

TDD, red→green per unit (the proven §2/§5/§6 loop).

- Pure units get focused JUnit tests with injected fakes:
  - `SphereUnion` — radius 1/2/3 counts and membership; negative coords.
  - `MaterialLut` — index 0 = void; stable append; repeated material → same index.
  - `GeometryAssembler` — heterogeneous section → correct `matIx`/`mass`; all-air section.
  - `HaloAssembler` — face order + face-cell indexing match §2; void at edges.
  - `StepValidator` — NaN/Inf rejected (keeps current); clamp to `[0,6000]`.
  - `ServerWorker` — drop on late/over-budget; climb after K; min/max clamps.
- `ConductionScheduler` — fake-seam-driven walk of IDLE→SNAPSHOT→AWAITING→WRITE-BACK,
  including: deadline miss holds previous temps; grace-then-cancel; NaN result rejected;
  dirty marking on write-back.
- One engine-integration test using `EngineFactory.create()` (real `liborge.so`) with
  hand-built fake seams — `assumeTrue`-guarded like §2's native tests — asserting a hot cell
  cools toward its neighbours after a step and the store is updated.
- Gate: keep the existing 101 `:core` tests green; both loader jars still build.

## Risks / notes

- **Rescan cost on the server thread.** SNAPSHOT scans 4096 blocks per section on the tick
  thread. For v1 ranges (≤4) this is acceptable; the version-keyed geometry cache is the
  documented perf follow-on. The cost is bounded by the health throttle (range drops if the
  *step* runs long, though range does not yet react to snapshot cost — noted for the cache
  track).
- **`mass` authority.** v1 derives `mass` from geometry (`defaultMass`) because the engine
  returns only temperatures. When Phase-2 fluid makes mass dynamic, mass authority migrates
  to `SectionData` and geometry reads it from there.
- **Single-worker range is global.** One server worker; its range applies to every player's
  sphere. Per-region throttling arrives with multi-worker distribution.

## Out of scope (deferred follow-on tracks)

- **Multi-worker distribution (the rest of §3/§8):** client workers, nearest-healthy-worker
  assignment + overflow, and the wire protocol
  (`ASSIGN`/`GEOMETRY`/`STEP_INPUT`/`STEP_RESULT`/`HEALTH`) over custom payloads, plus the
  client-side geometry cache.
- **Version-keyed geometry cache + block-change invalidation** (DESIGN §8 step 4) — v1
  rescans each step.
- **§7 phase change** — v1 writes temperatures back only; no block replacement.
- **Phase-2 mass simulation** — engine returns temps only; mass stays geometry-derived.
- **Real biome/material `AmbientProvider`** — §5 `FALLBACK` (285 K) still seeds initial
  temperatures; mass now comes from geometry, not the provider.
- **Datapack/config-driven tunables** — v1 uses constants.
