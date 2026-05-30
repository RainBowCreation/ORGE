# Engine-audit B+C — Heat Sources & Ambient Init — Design Spec

**Status:** approved (brainstorm 2026-05-30) — ready for `writing-plans`.
**Branch:** `rebuild` (stay on it; no PR/merge-to-main while v1 is incomplete).

## Goal

Make the inert thermal core actually evolve so the audit scenario works:

> Place water next to lava. Over several seconds the water heats, crosses 373.15 K, and
> becomes `orge:steam` — and the per-second temperatures match the finite-difference
> conduction math. A hot cell next to cold cells diffuses at the rate the kernel predicts.

Today the world is a flat 285 K with no heat sources, so conduction has no gradients to
spread, water never boils, and the only visible effect is an artifact: lava (seeded at
285 K, below its 1000 K freezing point) instantly freezes to stone. This spec fixes both
ends in one coupled change:

- **Topic C — Heat sources:** mark certain materials as **pinned** (fixed-temperature /
  Dirichlet) sources. Each second the engine diffuses from them; surviving source cells are
  re-pinned to their fixed temperature. Lava pinned hot also fixes the lava-freeze artifact.
- **Topic B — Ambient & init:** kill the flat 285 K. Seed never-simulated cells from biome
  temperature (bulk) and per-material `default_temperature` (sources), per cell.

B and C are combined because they **share one new `Material` field group**
(`default_temperature` + `pinned`): B reads it for seeding, C reads it for re-pinning.

A small **blockstate-aware binding** extension rides along because part of the agreed source
roster (lit campfire, lit furnace, powered redstone lamp, …) is distinguished by blockstate,
which the current block-id-only binding cannot express.

The **Locale.ROOT fix** for `OrgeCommandLogic` (P1 portability bug from Topic A's review) is
folded in as one small step.

No `liborge` / native engine change. No multi-worker, no networking, no Phase-2 fluids.

---

## Decisions (resolved in brainstorm)

### 1. Material field — the shared coupling
Add two **optional** fields to the `Material` record + `MaterialCodec`:

| JSON key | type | default | meaning |
|---|---|---|---|
| `default_temperature` | float (K) | absent → `Float.NaN` | natural/seed temperature; for pinned materials it is also the pin temperature |
| `pinned` | boolean | `false` | if true, surviving cells of this material are re-pinned to `default_temperature` each second (Dirichlet source) |

- Add `Material.hasDefaultTemperature()` → `!Float.isNaN(default_temperature)`.
- `MaterialCodec`: parse both as optional (mirror the existing optional-field handling);
  `pinned:true` **requires** `default_temperature` present (validation error otherwise).
- Existing fields, codec defaults, and all current material JSON remain valid unchanged.

### 2. Topic C — fixed-temperature sources, **conditional re-pin**
Chosen over power-injection (Neumann) because it needs **no kernel change** — it lives in the
existing scheduler / §5 / §7 seams. The pin is a **restoring force, not a lock**: a source
diffuses and is evaluated for phase change like any other cell, and only snaps back if it did
**not** transition.

**Per-second flow** (one simulation step = 20 ticks):

1. **Snapshot** — read stored temps. Surviving source cells already hold their pinned temp
   from the previous second's re-pin (or from first-touch seeding, §3).
2. **Engine step** — diffuse all cells; source cells lose/gain heat to neighbors normally.
3. **Write back** the diffused (validated) temps into `SectionData`.
4. **Phase change** — evaluate **every** cell's post-step temperature against its material's
   thresholds and swap blocks where crossed (existing §7 pass). A source overwhelmed past its
   own threshold transitions here and **ceases to be a source** (e.g. enough cold around a
   lava cell freezes it to stone).
5. **Re-pin** — for each cell that **was a `pinned` material at snapshot time** AND is **not**
   in the transition plan from step 4, reset its `SectionData` temperature to
   `default_temperature`.

Edge case this resolves: water that freezes to ice in step 4 is **not** re-pinned that same
second (it was not a source at snapshot). If its new material is itself pinned, it becomes a
pinned cell starting the **next** second. So "fresh transition into a pinned material" is
always deferred one second — no same-second snap.

Decorative sources carry **no** `boiling_target`/`freezing_target`, so they cannot transition
at all (a lit torch can't "freeze"); only lava (freeze → stone) participates in step 4 among
sources.

### 3. Topic B — biome ambient + per-cell seeding
- **Formula (pure, `core`):** `BiomeTemperature.toKelvin(base) = 285 + (base − 0.8) × 20`.
  Temperate biome base ≈ 0.8 → exactly 285 K (= the existing `DEFAULT_AMBIENT_K`), so the flat
  fallback equals the temperate result. Snowy(0.0) → 269 K, desert(2.0) → 309 K. Clamp the
  result to the engine's sane range `[0, 6000]` K defensively.
- **Biome lookup:** vanilla, identical on both loaders — the MC seam reads
  `level.getBiome(pos).value().getBaseTemperature()` directly. **No new `ExpectPlatform`
  seam.** Sample **once per section** at the section's center block (biome varies slowly;
  4096 lookups/section is wasteful).
- **Fallback:** when no biome is available, ambient = `SectionData.DEFAULT_AMBIENT_K` (285 K);
  `AmbientProvider.FALLBACK` stays as the documented default.
- **Per-cell seed (pure):** for a never-simulated section,
  `T0[i] = mat(i).hasDefaultTemperature() ? mat(i).default_temperature : biomeAmbientK`.
  This guarantees source cells (lava) start hot on the **first** snapshot, so they never
  freeze before the first re-pin runs.
- **Scope:** seed **only never-simulated sections** (no persisted orge data). Sections with
  persisted data load their evolved gradient untouched. Pre-existing worlds need **no
  migration pass** — a never-touched section is seeded lazily the first time it is simulated.

### 4. Blockstate-aware binding
Extend `MaterialBindings` so an override or tag binding key may carry an optional blockstate
predicate: `<id>[<prop><op><val>,...]` (e.g. `minecraft:campfire[lit=true] → orge:campfire`,
`#minecraft:candles[lit=true] → orge:candle`, `minecraft:redstone_wire[power>0] → orge:...`).

- **Operators:** two — `=` (equality, string compare against the property value) and `>`
  (numeric greater-than, for integer properties like `power` 0–15 so `power>0` means
  "energised"). `>` parses both operands as integers; a non-numeric property value fails the
  match (never throws).
- **Pure parsing/matching (`core`):** parse `id[prop<op>val,...]` into a block id + an ordered
  list of `(prop, op, val)` requirements. Predicated entries for a block id are checked
  **first** (all requirements must match) → then plain block-id override → then tag bindings
  (predicated tag bindings match if the tag contains the block AND the predicate holds) →
  then fallback.
- **Property access (loader-agnostic):** `materialFor` takes a `PropertyView`
  (`String name → String value`, returns null if absent) instead of bare block id. The MC seam
  adapts a `BlockState` into a `PropertyView` that reads **only** the property names referenced
  by predicates for that block id — so blocks with no predicate bindings (the overwhelming
  majority, e.g. stone) cost nothing extra.
- Backward-compatible: a binding key with no `[...]` parses to zero requirements and behaves
  exactly as today.

### 5. Source roster (this spec)
Tiers 1 + 2 (block-id + blockstate predicate). "Redstone components when powered" folds into
Tier 2 via the predicate binding (it is blockstate-expressible, not a new mechanism — see the
powered-redstone group below). Tiers 3–4 (brewing-stand **block-entity** state, `end_crystal`
**entity**) remain **deferred** — each needs a genuinely new mechanism beyond the
material/binding model. `copper_torch`/`copper_lantern` are **dropped** (not vanilla in
1.21.11).

Each source is a `Material` with `pinned:true`, a `default_temperature`, modest thermal
constants, and **no** phase targets (except lava, which keeps `freezing_target = stone`).
Starting temperatures below are **proposals, tunable at the review gate**. Blocks sharing a
temperature may share a source material; lava keeps its existing `lava.json`.

**HOT — Tier 1 (block id, always on):**

| block(s) | default_temperature (K) |
|---|---|
| `minecraft:lava` | 1400 |
| `minecraft:fire` | 1100 |
| `minecraft:nether_portal` | 800 |
| `minecraft:torch`, `minecraft:wall_torch` | 800 |
| `minecraft:lantern` | 700 |
| `minecraft:magma_block` | 600 |
| `minecraft:glowstone` | 500 |
| `minecraft:redstone_block` | 400 |

**HOT — Tier 2 (blockstate predicate):**

| binding | default_temperature (K) |
|---|---|
| `minecraft:campfire[lit=true]` | 1000 |
| `minecraft:furnace[lit=true]`, `minecraft:blast_furnace[lit=true]`, `minecraft:smoker[lit=true]` | 900 |
| `minecraft:redstone_torch[lit=true]`, `minecraft:redstone_wall_torch[lit=true]` | 600 |
| `#minecraft:candles[lit=true]` (base + dyed via tag) | 600 |
| `minecraft:redstone_lamp[lit=true]` | 500 |
| `minecraft:copper_bulb[lit=true]` (+ oxidation/waxed variants, enumerated) | 500 |
| `minecraft:lightning_rod[powered=true]` (transient) | 1500 |
| **powered redstone components** (shared temp, enumerated set below) | 400 |

**HOT — Tier 2, powered-redstone-components group** (all `pinned`, shared 400 K, tunable;
enumerated because vanilla has no "redstone components" tag). Grouped by the property that
signals power:

| power signal | blocks |
|---|---|
| `[powered=true]` | `repeater`, `comparator`, `observer`, `lever`, all buttons (`*_button`), boolean pressure plates (`stone_pressure_plate`, `polished_blackstone_pressure_plate`, wood `*_pressure_plate`), `tripwire_hook`, `powered_rail`, `detector_rail`, `activator_rail`, `note_block` |
| `[extended=true]` | `piston`, `sticky_piston` |
| `[triggered=true]` | `dispenser`, `dropper`, `crafter` |
| `[power>0]` | `redstone_wire`, `daylight_detector`, `target`, `light_weighted_pressure_plate`, `heavy_weighted_pressure_plate`, `sculk_sensor`, `calibrated_sculk_sensor` |

> The `[power>0]` rows are exactly why the `>` operator (§4) exists. `redstone_torch[lit]`
> (600 K), `redstone_lamp[lit]` (500 K) and `redstone_block` (400 K) keep their own listed
> entries and are not part of this shared group.

**COLD — Tier 1 (block id):**

| block(s) | default_temperature (K) |
|---|---|
| `minecraft:end_rod` | 250 |
| `minecraft:soul_fire` | 250 |
| `minecraft:blue_ice` | 250 |
| `minecraft:soul_lantern` | 260 |

**COLD — Tier 2 (blockstate predicate):**

| binding | default_temperature (K) |
|---|---|
| `minecraft:soul_campfire[lit=true]` | 255 |

> Note: `minecraft:ice`/`packed_ice`/`snow`/`snow_block`/`powder_snow` are **not** pinned
> sources (per the roster decision) — they participate in phase change normally. `blue_ice`
> *is* a cold source. `candles`/`copper_bulb` variant families are covered by a tag-predicate
> binding (candles) or enumerated overrides (copper bulb oxidation/waxed states).

### 6. Locale.ROOT fix
Add `Locale.ROOT` to all `String.format(...)` calls in `OrgeCommandLogic` (11 sites) so a
non-EN-locale JVM does not emit `"285,00 K"` and break `contains` assertions. Pure mechanical
change with a regression test asserting `.` decimal output under a non-EN default locale.

---

## Architecture & seams

Mirror the established pure-logic-behind-a-Minecraft-seam convention (`PhasePlanner` /
`MinecraftPhaseChanger`, `Scheduler` / `MinecraftThermalWorld`).

**New / changed pure `core` logic (unit-tested in isolation):**
- `Material` (+2 fields, `hasDefaultTemperature()`), `MaterialCodec` (+2 keys, validation).
- `BiomeTemperature.toKelvin(float base)` → clamped Kelvin.
- `AmbientSeeder` (pure): given a per-cell material accessor + a section biome-ambient K,
  produce `float[4096]` seed temps (`default_temperature ?? ambient`).
- `SourcePinPlanner` (pure): given a per-cell material accessor + the §7 transition plan,
  produce the list of `(cellIndex, default_temperature)` resets (pinned ∧ not transitioned).
- `MaterialBindings` (extended): predicate parsing + `PropertyView`-based `materialFor`.
- `PropertyView` (new functional interface in `core`).

**MC-touching seams (in `core`, may import Minecraft like the existing seams):**
- `MinecraftThermalWorld`: replace the flat-285 `ambientTemps()` path with per-cell
  first-touch seeding (biome sample → `BiomeTemperature` → `AmbientSeeder`), persisted to the
  store; detect "never-simulated" via the section store.
- `MinecraftPhaseChanger`: after applying block swaps, run `SourcePinPlanner` and write the
  re-pin resets into `SectionData` (it already has the transition plan + store/block access).
- `LiveMaterials` / `blockAt`: thread `BlockState` (not just `Block`) so the `PropertyView`
  can read blockstate properties for predicate bindings.

**Wiring:** `Orge.java` keeps passing an `AmbientProvider`; the per-section biome ambient is
computed in the seam. The deferred-`AmbientProvider`-TODO is resolved.

**Ordering invariant (the audit-critical contract):** snapshot → step → writeBack →
phaseChange → re-pin, per section, every second. Re-pin is the last write so a surviving
source always ends the second at exactly `default_temperature`.

---

## Testing

Pure units fully TDD'd in `core`:
- `MaterialCodec`: new `default_temperature`/`pinned` round-trip; defaults (absent → NaN /
  false); `pinned:true` without `default_temperature` → error; existing JSON still decodes.
- `BiomeTemperature`: anchor (0.8→285), snowy/desert/negative, clamp.
- `AmbientSeeder`: source cell → `default_temperature`; bulk cell → ambient; mixed section.
- `SourcePinPlanner`: pinned-not-transitioned reset; pinned-but-transitioned skipped;
  non-pinned never reset; fresh-transition-into-pinned deferred (not reset this step).
- `MaterialBindings`: predicate parse (`id[a=b,c=d]`), `=` match/no-match, `>` numeric match
  (`power>0` true for 1–15, false for 0, false for non-numeric value), predicate-over-plain
  precedence, tag-predicate, no-predicate backward compatibility, `PropertyView` reads only
  referenced props.
- `OrgeCommandLogic`: `.`-decimal output under a forced non-EN default locale.

**Audit scenario (headless integration-style test over the pure flow):** seed a small grid
with a pinned lava cell adjacent to water cells (+ ambient bulk), run repeated
step → phaseChange → re-pin cycles against the real engine/stub, and assert: (a) water temps
rise monotonically toward the finite-difference prediction, (b) water crosses 373.15 K and the
§7 plan emits `orge:steam`, (c) lava stays pinned at ~1400 K across seconds and never freezes,
(d) per-step deltas match the kernel within tolerance.

**In-game demo** (place water next to lava, `/orge get` to watch it climb) remains **pending a
dev client** (no `runClient` in this sandbox) — adapter paths are compile-verified; behavior is
covered by the headless tests. Noted, not a blocker.

## Definition of done
- This spec + a `writing-plans` plan committed.
- Each plan task committed; `./gradlew :core:test` green (new tests + no regressions);
  all 3 loaders compile (`:core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`);
  `./gradlew build` succeeds.
- Locale.ROOT fix landed.
- Audit scenario validated by headless tests; in-game demo noted pending.
- Push `origin/rebuild` only at the end. Add `orge-engine-audit-bc` memory; update `MEMORY.md`.

## Out of scope (deferred)
- Tier 3 (brewing-stand block-entity sources) and Tier 4 (`end_crystal` entity sources) —
  each needs a new mechanism (block-entity probing / entity heat sources). (Powered redstone
  components are **in scope** via the Tier-2 predicate binding, §5.)
- `copper_torch`/`copper_lantern` (not vanilla 1.21.11).
- §3 multi-worker distribution / wire protocol; §10 Phase-2 fluids; client-cache transport.
- Latent heat, temperature-dependent material curves, mass conservation (Phase 2).

### Deferral decision — 2026-05-30 (post B+C, post in-game audit)
After B+C landed and the in-game audit confirmed the conduction core works (sources
seed, water boils next to lava, lava holds; two scheduler bugs found + fixed), we are
**explicitly skipping the following "for now"** — they are NOT on the immediate roadmap:

1. **Block-entity heat sources** (Tier 3, e.g. brewing stand).
2. **Entity heat sources** (Tier 4, e.g. `end_crystal`).
3. **Fluid / gas handler** (§10 Phase-2: fluid flow, gas diffusion/buoyancy, latent heat,
   mass conservation).

**Why deferred (the constraint that makes these different from B+C):** all three likely
require either a **second engine** or a **modification to the ORGE (`liborge`) engine
itself** — B+C deliberately required *no* native/engine change (it is pure orchestration +
data over the existing stateless conduction `step()`). Block-entity/entity sources need a
new per-tick probing path that does not map onto the current section/cell block-derived
model; fluid/gas needs advective transport (mass actually moving between cells), which the
current pure-conduction kernel does not model. Each is its own track: brainstorm → spec →
plan, and each must first decide the engine question (extend `liborge` vs. a new engine vs.
a server-side pre/post pass) before any implementation. Do **not** start them as a
continuation of this plan.
