> **SUPERSEDED re: material LUT** — see `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`.
> `matIx` ids are now globally STABLE (fixed per material at load/`/reload`, slot 0 = VACUUM, slots 1..N
> = `MaterialRegistry.all()` sorted by namespaced id). The LUT is engine-resident (register-once via
> `orgeRegisterMaterials`), NOT shipped per `orgeStepWorld` call. Passages below describing a per-step /
> batch-local / first-seen LUT are historical.

# Unified Fluid Engine — design spec

**Date:** 2026-06-01
**Status:** APPROVED MODEL, pre-implementation. Supersedes the advection model of
`2026-06-01-whole-region-engine-step-design.md` (that pivot's *orchestration* is kept; its *sectioned
advection rules* are replaced).

---

## ⛔ STOP — READ THIS BEFORE TOUCHING THE ENGINE (the guard)

This has been specified before and built wrong. The previous engine re-introduced the sectioned, gas/liquid-
branched model the design explicitly rejected. **These invariants are non-negotiable. If your change
violates any of them, you are building the OLD engine again — stop.**

1. **There is ONE substance, and EVERY cell runs the identical flow calculation.** Stone, water, air, sand —
   all go through the same passes. The engine has **no** concept of "gas"/"liquid"/"solid", no `gasFlag`,
   no fluid-vs-solid filter anywhere, and **no `state` field at all** (dropped — `representative_block` covers
   rendering). Java hands the engine the **whole chunk, unfiltered, exactly like conduction does** — it never
   decides what is or isn't a fluid.

   **Immovability is data, not a branch:** `viscosity` is physical resistance — `0` = fastest flow, higher =
   slower, and **absent = frozen** (marshalled as `+∞`, so its flow rate is `0`). A frozen cell (most solid
   terrain) cannot flow, sort, or be displaced. A solid that *should* move (sand, gravel) just gets a finite
   `viscosity` with `min_mass ≈ max_mass`, so it sorts by molar mass as a coherent block without spreading —
   "falling blocks" emerge with zero special-case code. The single test **movable ⟺ flow-rate > 0** (i.e.
   viscosity finite) replaces every fluid/solid branch.

2. **A cell's MOTION is fully described by four numbers:** `min_mass` (relaxed per-cell mass), `max_mass`
   (per-cell compression ceiling), `molar_mass` (sort key), `viscosity` (physical resistance: `0` = fastest,
   higher = slower ooze, **absent = frozen/∞**). "Incompressible" vs "compressible" is just **narrow vs wide
   `min_mass`..`max_mass`**; "solid" vs "flowing" is just absent vs finite `viscosity` — all per-cell data,
   NOT engine branches. The full schema is below; there is **no** `min_flow_mass`, **no** `state`, and **no**
   boolean `fluid`/`gas`/`air` flag.

3. **There is NO "fall" rule.** Gravity is expressed *only* as **sort by molar mass: heavier sinks, lighter
   rises.** "Falling" is the special case of a heavy fluid sorting below the lightest fluid (void/air). Do not
   write a gravity/fall pass that is separate from the molar-mass sort.

4. **Sections and chunks are STORAGE ONLY. The physics never sees a 16-boundary.** No `% SECTION_EDGE`, no
   `y % 16`, no "seam" pass, no "cross-seam" special case, no halo. Every pass walks the **full column height
   (0..CHUNK_H-1)** and across **all loaded chunks** as one continuous medium. *This is the bug that keeps
   coming back* — see "Why the old engine was wrong" below.

5. **Void is just the lightest fluid:** `molar_mass = 0, min_mass = 0, max_mass = 0`, with a **finite**
   `viscosity` (so it can be displaced). "Expand into vacuum" is not a special case — it is "I am heavier than
   void, so I sort below it." Air is merely a slightly-heavier fluid. (Only true solids omit `viscosity` →
   frozen.)

6. **One species per cell. Never a mixture.** A cell holds exactly one fluid id + its mass. Displacing a
   lighter fluid relocates it whole; it is never blended in.

7. **Conservation is by construction + backstop.** Every transfer is antisymmetric from a single pre-step
   snapshot (donor `-dm`, receiver `+dm`), so each species' total mass is invariant. The region-wide
   `SpeciesMassLedger` HOLDs (writes nothing) any cycle that still fails to balance.

If you find yourself writing `if (gasFlag)`, `y % SECTION_EDGE`, a `fall` pass, or a `seam` pass — you have
left the design. Re-read this section.

---

## The model

Every fluid material carries:

| field | meaning | "water-ish" example | "air-ish" example | void | stone |
|---|---|---|---|---|---|
| `min_mass` | relaxed mass per cell — a free body expands until every occupied cell is near this | 125 kg | 1 kg | 0 | 2500 |
| `max_mass` | compression ceiling — squeeze the world into one cell and it holds this, no more | 1000 kg | 50 kg | 0 | 2500 |
| `molar_mass` | gravitational sort key — higher sinks | 18 | 2 | 0 | 60 |
| `viscosity` | resistance: `0` = fastest, higher = slower ooze, **absent = frozen** | low | low | low | *(absent)* |

Note stone: `min_mass == max_mass` (incompressible) and **no `viscosity`** (frozen) → it never moves or
compresses. Sand would be identical but *with* a finite `viscosity` → it sorts (falls) as a coherent block.

Two emergent behaviours, no branches:

- **"Pools with a surface"** = a fluid with a *narrow* `min`..`max` (nearly incompressible): it can't pack
  upward, so it sorts down and spreads to a flat level.
- **"Fills the container"** = a fluid with a *wide* `min`..`max` (compressible): it expands to fill all
  accessible volume toward `min`, compressing toward `max` only when boxed in.
- **"Floats / sinks"** = the molar-mass sort. Oil-on-water (two narrow-band fluids) uses the identical rule
  as air-on-water.

### Worked examples (these become acceptance tests)

- **Expand in void.** 1000 kg of fluid (`min 125`) placed in a void world spreads until ~8 cells each hold
  ~125 kg, occupying the **lowest** 8 accessible cells (molar sort fills bottom-up). Total stays 1000.
- **Compress.** Shrink that world to one sealed cell → it packs to `max` (1000 kg) in that cell; any excess
  beyond `max` stays distributed in neighbours (can't exceed `max` anywhere).
- **Sort / fall.** One heavy-fluid cell at the top of a tall sealed column of light gas sinks one cell per
  step — **crossing every section boundary** — until it rests on the floor; the displaced gas ends up above
  it. Total of each species invariant. *(This is the tube case the old engine got wrong.)*
- **Stratify, don't annihilate.** Heavy gas over light gas in a sealed 2-cell box → they swap to light-over-
  heavy; final is one cell of each, both conserved (no cell is ever emptied of a species that had nowhere to
  go).

---

## Material data schema (canonical — refactor target)

**The current material data is wrong and must be refactored to exactly this schema — no more, no fewer
fields.** A material's JSON (`data/orge/orge/materials/<name>.json`), the `Material` record, the loader, the
engine LUT, and the phase system all conform to this:

**REQUIRED — the loader errors if any is missing** (clean data is mandatory):

| field | type | who uses it | meaning |
|---|---|---|---|
| `thermal_conductivity` | float | **engine** (conduction) | W/(m·K) |
| `heat_capacity` | float | **engine** (conduction) | J/(kg·K) |
| `molar_mass` | float | **engine** (sort) | gravitational sort key — higher sinks |
| `default_mass` | float | Java (seed) | kg placed in a cell when this material is first created |
| `default_temperature` | float | Java (seed) | natural/seed temperature (K) |

**OPTIONAL — each has a default when absent** (keeps JSON minimal):

| field | type | who uses it | absent ⇒ default | meaning |
|---|---|---|---|---|
| `viscosity` | float | **engine** (advection) | **frozen** (marshalled as `+∞`) | resistance: `0` = fastest flow, higher = slower ooze. **Omit it for a static solid** (frozen). |
| `min_mass` | float | **engine** (advection) | `= default_mass` | relaxed per-cell mass; a free body expands until cells near this. (Replaces `min_flow_mass`.) |
| `max_mass` | float | **engine** (advection) | `= default_mass` | per-cell compression ceiling; a cell never exceeds this. |
| `min_temp` | float | Java (phase) | no cold phase change | below this the cell becomes `min_target`. **If present, `min_target` is required.** |
| `max_temp` | float | Java (phase) | no hot phase change | above this the cell becomes `max_target`. **If present, `max_target` is required.** |
| `min_target` | **material id** | Java (phase) | — | material to become below `min_temp` (e.g. `orge:ice`) — a **material id, never a block id**. |
| `max_target` | **material id** | Java (phase) | — | material to become above `max_temp` (e.g. `orge:steam`). |
| `representative_block` | **block id** | Java render | **`minecraft:air`** | the Minecraft block drawn for this material. **Not the identity** — see note. |
| `pinned` | bool | Java (Dirichlet) | `false` | when true, the cell temperature is held at `default_temperature` every tick (heat source/sink). |

**`state` is dropped** — `representative_block` covers rendering and `viscosity` covers movement, so the field
is redundant. It is not in the schema, the `Material` record, or anywhere in the engine.

### `representative_block` is NOT the material identity

A cell's identity is its **material id**; `representative_block` is only what's *drawn*. Multiple distinct
materials may share one representative block. An **invisible gas** is exactly this: `representative_block:
minecraft:air`, yet a unique material with its own `molar_mass`/`viscosity`/etc. — each cell stays that gas,
physically distinct from real air, even though both render as `minecraft:air`. Identity lives in the material,
never in the block.

### The material-id ⇄ block-id indirection (the core fix)

Phase targets and identity are expressed in **material ids**, not block ids. A material id (`orge:air`) names
a material JSON (`air.json`); that material's `representative_block` (`minecraft:air`) is the block used to
*render* it. So phase change is **material → material** (`water` → `orge:steam`), and rendering is a separate
**material → block** lookup (`orge:steam` → its `representative_block`). The current data violates this —
e.g. `water.json` has `"min_target": "minecraft:ice"` (a **block** id). It must become a **material** id
(`orge:ice`), and an `ice` material must exist with `representative_block: minecraft:ice`.

### The `viscosity` model (physical resistance) — LOCKED

`viscosity` is **physical resistance**, keeping textbook direction: `0` = fastest flow, higher = slower.
The per-step spread rate is `clamp(K / viscosity, 0, CFL_cap)` — so `viscosity = 0` hits the stability cap
(fastest), and a large value oozes. **Absent ⇒ frozen**: marshalled as `+∞`, giving rate `0` — the cell
never spreads, and (because **movable ⟺ rate > 0**) it is never picked up by the molar-sort swap either, so
solid terrain stays put. This is the ergonomic default: most blocks are solid, so they simply omit
`viscosity`.

**v1 decision — viscosity throttles SPREAD only, not vertical sink:** the molar-mass sort is a **full-cell
binary swap** (one-species-per-cell forbids a partial/blended swap), fired whenever two **movable** cells are
out of gravitational order — at the engine's rate, regardless of viscosity magnitude. So a viscous fluid
(lava) *drips down* at full speed but *spreads sideways* slowly; a frozen cell does neither. (Throttling
vertical sink by viscosity is a banked v2 refinement — it needs per-cell state the stateless engine avoids.)

### What's wrong with the current data (the refactor checklist)

1. `min_flow_mass` → **rename to `min_mass`** (absent ⇒ `default_mass`); drop the `Material.maxMass()`
   "0 means fall back to default" hack — defaulting is the loader's job, not a getter's.
2. `min_target`/`max_target` hold **block** ids (`minecraft:ice`, `minecraft:water`) → must be **material**
   ids (`orge:ice`, `orge:water`); create the missing target materials.
3. `viscosity` becomes optional with **physical semantics**: `0` = fastest, higher = slower, **omit = frozen**
   (`generic_solid.json` should have NO `viscosity`). A movable solid (sand) gets a finite `viscosity` +
   `min_mass ≈ max_mass`. (The old "`0` = non-fluid" meaning is gone.)
4. `default_temperature` is now **required on every material** (no `NaN`/absent); the loader errors if a
   required field is missing.
5. **`state` is removed entirely** — from the JSON, the `Material` record, and the engine LUT. The old `State`
   enum `{SOLID, FLUID, GAS, ENTITY, AIR}` and every boolean `fluid`/`gas`/`air` flag go with it.
6. Remove every field not in the schema above; add every field that is.

## Sort key = molar mass ONLY (locked)

Float order is decided by the fluid's **fixed `molarMass`**, never its current (compressed) cell mass. A
heavily compressed light gas whose kg/cell exceeds a liquid's resting mass **still rises** — compression is
just packing, identity decides order. (The old engine sorted gas by *current* mass; that is explicitly
rejected — it made float order depend on compression and produced the wrong stratification.)

---

## Architecture — what changes, what stays

The 2026-06-01 whole-region pivot got the **data flow** right. We keep all of it and rebuild **only the
advection internals**.

**KEEP (unchanged):**
- Whole-region `World` step: Java assembles active+apron **full-height columns**, one `orgeStepWorld` JNI
  call builds a transient `World`, returns the next state. (`Scheduler`, `MinecraftThermalWorld.snapshotColumns`,
  `ColumnAssembler` signature-gated seed, `RegionMarshaller`, `ColumnSectionCodec`, write-back.)
- **Conduction** (`compute_frame_to_backbuffers` + `swap_all_backbuffers`): already per-cell with live
  cross-chunk neighbour reads — already section-agnostic. Untouched.
- Region-wide `SpeciesMassLedger` HOLD backstop; signature-gated seed; air-aware `MinecraftFluidReconciler`
  (engine output = truth, never re-derives mass).
- The whole-region `orgeStepWorld` FFI *shape* (per-column arrays + a per-material LUT). The LUT **contents**
  change to the canonical schema below: the engine LUT carries **only** the six physics numbers
  (`thermal_conductivity`, `heat_capacity`, `molar_mass`, `min_mass`, `max_mass`, `viscosity`). No `state`,
  no movable bit, no boolean `fluid`/`gas`/`air` flag — immovability falls out of `viscosity` being `+∞`
  (absent), i.e. flow-rate `0`. Java sends every cell of every assembled column (no fluid filtering), as
  conduction already does.

**REBUILD (`advect_chunk` internals → section-agnostic unified-fluid passes):**

The current `advect_chunk` is a zoo of section-local passes (`fall`, `2`/spread, `2a''`/horizontal-gas-
displace, `2c`/buoyancy-swap, `2c-seam`, `2c-seam-air`, `2d`/volume-fill) each gated on `gasFlag` and
`% SECTION_EDGE`. **Replace the entire body with two section-agnostic passes over the full column + loaded
neighbours, both driven off one pre-step `WorldSnapshot`:**

- **Pass A — molar-mass sort (gravity).** For every vertical neighbour pair across the *entire* column height
  (and within the World, so a column is one contiguous medium), if the upper cell's `molar_mass` exceeds the
  lower cell's **and both cells are movable** (`viscosity` finite), **full-cell swap** them toward sorted
  order (a full swap, never partial — one-species-per-cell forbids blending). A frozen cell (`viscosity` ∞)
  is never swapped, so fluid can't sink through terrain. One swap/step; iterates to fully sorted. No section
  skip, no viscosity-magnitude throttle (v1). This single pass is *both* "fall" and "buoyancy".
- **Pass B — relax toward `min_mass`, cap at `max_mass`.** For every movable cell above its `min_mass`,
  distribute the surplus to the 6-dir accessible neighbours (across all boundaries) below their fill, bringing
  cells toward `min_mass`; when boxed in, mass compresses up to `max_mass` and no further. Same-species
  merges; a different-species neighbour is displaced via the sort (Pass A), preserving one-species-per-cell.
  The transfer fraction is `clamp(K / viscosity, 0, CFL_cap)` — so `viscosity 0` spreads fastest and a frozen
  cell (∞) doesn't spread at all.

Both passes write only their **own** cell, deriving `dm` from the shared snapshot → antisymmetric →
per-species conservation by construction. A settle deadband (relax only when off-target by more than a small
ε, and a molar-sort hysteresis) prevents checkerboard oscillation; settled cells feed the §10 dormancy gate.

*(Exact transfer-fraction math, ε/hysteresis values, and pass ordering are pinned in the implementation
plan via TDD. This spec fixes the model and invariants; the plan fixes the numbers.)*

**DELETE:**
- `orge_kernel.hpp` (the dormant per-section kernel) and `advection_parity_test.cpp`. They *are* the
  sectioned model we are abandoning; keeping them is a trap that invites "restore parity" regressions.
- Every `% SECTION_EDGE` / seam / halo path inside advection.
- Every `gasFlag`-gated behavioural branch.

---

## Why the old engine was wrong (the evidence, so it isn't repeated)

The whole-region pivot fixed the **data** (one `World`) but left the **rules sectioned**. In
`sim_engine.hpp` the buoyancy swap pairs `(y-1, y)` *only* when `y % SECTION_EDGE != 0` (the interior `(2c)`
loop), and the boundary plane is patched by separate bolted-on `(2c-seam)` / `(2c-seam-air)` blocks that
cover only specific cases (e.g. the T0.5 fix un-banked liquid-into-*air* but not the general fluid-into-
fluid sort). So a heavy fluid sinking through a light gas across a section line hits the `% SECTION_EDGE`
wall and **stops** unless a seam patch happens to match — the exact "drops to the section floor and stops"
bug, on Y and identically on the X/Z chunk seams (the `wetPair` patch is separate from interior spread).
Root cause: **the section (a storage detail) leaked into the physics.** The rebuild removes the leak by
construction — the physics walks the full medium and never branches on a boundary or a phase.

---

## Locked decisions

1. **Compressibility is per-fluid `min_mass`..`max_mass`, engine-agnostic.** No "liquid"/"gas" category.
2. **One species per cell** — displacement relocates whole, never blends. The molar sort is a full-cell swap.
3. **Sort by `molar_mass` only** — compression never changes float order.
4. **`viscosity` = physical resistance** — `0` = fastest, higher = slower, absent = frozen (∞). v1 throttles
   horizontal spread only; vertical sink is full-rate for any movable cell.
5. **Settle deadband + molar-sort hysteresis** — relax/swap only past a small ε (no eternal micro-flow).
6. **Sections/chunks are storage only** — physics is boundary-agnostic, full-column + loaded-neighbour.
7. **Void = lightest fluid** (`0/0/0`, finite viscosity) — unifies vacuum/air/void into one continuum.
8. **`state` dropped; material data conforms to the canonical schema** — exactly those fields; `min_mass` not
   `min_flow_mass`; phase targets are material ids; required fields error if missing. Refactoring the existing
   (wrong) data is in scope.

---

## Acceptance oracles (must be green on the REAL `.so`, live pipeline)

1. **Tube sort across sections.** Sealed Y-column of light gas, multiple sections tall; drop one heavy-fluid
   cell at the top → it reaches the floor (crossing ≥2 section boundaries), gas ends up above, each species'
   total exactly conserved. *(The old engine fails this.)*
2. **Cross-chunk X/Z.** Heavy fluid poured at a chunk edge crosses into the dry neighbour chunk and levels;
   total conserved; no doubling, no stall at the seam.
3. **`==1000.0` repro.** The existing `Section11LivePipelineReproTest` symptoms (place / spread / cross /
   non-conservation) stay exactly 1000.0 through the live pipeline.
4. **Stratify-not-annihilate.** Sealed 2-cell box, heavy gas over light gas → one cell each after sorting;
   both conserved.
5. **Void unification.** A fluid expands into a region of `0/0/0` void exactly as into air; no special-case
   code path exists for void.
6. **Region ledger HOLD.** Any injected non-conservation freezes the region (nothing written), never leaks.
7. **Frozen solid stays (no state branch).** A stone shelf (no `viscosity` → ∞) under a heavy fluid never
   lets the fluid sink through it and never moves itself; the engine contains no `state`/solid conditional —
   only the `viscosity`-finite movability test. Stone still conducts heat normally.
8. **Falling block emerges.** A column material with a finite `viscosity` and `min_mass ≈ max_mass` placed in
   air drops one cell/step as a coherent block (no sideways spread), rests on a frozen floor, mass exactly
   conserved — with zero falling-block-specific code.
9. **Viscosity affects spread rate, not sink rate (v1).** A high-`viscosity` fluid and a low-`viscosity`
   fluid both sink at the same rate, but the high-`viscosity` one spreads sideways measurably slower.

---

## Out of scope (banked)

- Pressure/EOS continuous solver (this stays a discrete cellular relaxation; `min`/`max` approximate the
  equation of state).
- Mixtures / dissolved gas (one species per cell, decided above).
- Surface tension, temperature-driven phase change physics (phase change stays in the Java `PhaseChanger`).
- Y-band column trimming perf optimization (full-height columns for now).
