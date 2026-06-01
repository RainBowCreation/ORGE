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

1. **There is ONE substance: `fluid`.** The engine has **no** concept of "gas" or "liquid". No `gasFlag`
   branch, no `liquid` branch, no per-phase code path. "Looks like water" vs "looks like air" is a **render
   label in Java only**, derived from the material id — the engine never knows or cares.

2. **A fluid's MOTION is fully described by four numbers:** `min_mass` (relaxed per-cell mass), `max_mass`
   (per-cell compression ceiling), `molar_mass` (sort key), `viscosity` (flow *rate* only). "Incompressible"
   vs "compressible" is just **narrow vs wide `min_mass`..`max_mass`** — a per-fluid data setting, NOT an
   engine branch. The full material schema is below ("Material data schema"); there is **no** `min_flow_mass`
   and **no** boolean `fluid`/`gas`/`air` flag.

3. **There is NO "fall" rule.** Gravity is expressed *only* as **sort by molar mass: heavier sinks, lighter
   rises.** "Falling" is the special case of a heavy fluid sorting below the lightest fluid (void/air). Do not
   write a gravity/fall pass that is separate from the molar-mass sort.

4. **Sections and chunks are STORAGE ONLY. The physics never sees a 16-boundary.** No `% SECTION_EDGE`, no
   `y % 16`, no "seam" pass, no "cross-seam" special case, no halo. Every pass walks the **full column height
   (0..CHUNK_H-1)** and across **all loaded chunks** as one continuous medium. *This is the bug that keeps
   coming back* — see "Why the old engine was wrong" below.

5. **Void is just the lightest fluid:** `molarMass = 0, min = 0, max = 0`. "Expand into vacuum" is not a
   special case — it is "I am heavier than void, so I sort below it." Air is merely a slightly-heavier fluid.

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

| field | meaning | "water-ish" example | "air-ish" example | void |
|---|---|---|---|---|
| `min` | relaxed mass per cell — a free body expands until every occupied cell is near `min` | 125 kg | 1 kg | 0 |
| `max` | compression ceiling — squeeze the world into one cell and it holds `max`, no more | 1000 kg | 50 kg | 0 |
| `molarMass` | gravitational sort key — higher sinks | 18 | 2 | 0 |
| `viscosity` | flow-rate damping only (how fast it relaxes, not whether) | high | low | — |

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

| field | type | who uses it | meaning |
|---|---|---|---|
| `thermal_conductivity` | float | **engine** (conduction) | W/(m·K) |
| `heat_capacity` | float | **engine** (conduction) | J/(kg·K) |
| `default_mass` | float | Java (seed) | kg placed in a cell when this material is first created |
| `molar_mass` | float | **engine** (sort) | gravitational sort key — higher sinks |
| `max_mass` | float | **engine** (advection) | per-cell compression ceiling (kg); a cell never exceeds this |
| `min_mass` | float | **engine** (advection) | relaxed per-cell mass (kg); a free body expands until cells near this. **Replaces `min_flow_mass`.** |
| `state` | enum | Java render **+** engine solid-gate | `solid` \| `liquid` \| `fluid`. See note below. |
| `viscosity` | float | **engine** (advection) | flow-*rate* damping only (Pa·s) |
| `default_temp` | float | Java (seed) | natural/seed temperature (K) |
| `pinned` | bool | Java (Dirichlet) | when true, the cell temperature is held at `default_temp` every tick (heat source/sink) |
| `min_temp` | float | Java (phase) | below this the cell becomes `min_target` |
| `max_temp` | float | Java (phase) | above this the cell becomes `max_target` |
| `min_target` | **material id** | Java (phase) | material to become below `min_temp` (e.g. `orge:ice`) — **a material id, never a block id** |
| `max_target` | **material id** | Java (phase) | material to become above `max_temp` (e.g. `orge:steam`) |
| `representative_block` | **block id** | Java render | the Minecraft block that renders this material (e.g. `minecraft:air`) |

### The material-id ⇄ block-id indirection (the core fix)

Phase targets and identity are expressed in **material ids**, not block ids. A material id (`orge:air`) names
a material JSON (`air.json`); that material's `representative_block` (`minecraft:air`) is the block used to
*render* it. So phase change is **material → material** (`water` → `orge:steam`), and rendering is a separate
**material → block** lookup (`orge:steam` → its `representative_block`). The current data violates this —
e.g. `water.json` has `"min_target": "minecraft:ice"` (a **block** id). It must become a **material** id
(`orge:ice`), and an `ice` material must exist with `representative_block: minecraft:ice`.

### `state` semantics (note + one open question)

- `solid` → **immovable**. The engine's *only* behavioural read of `state`: a solid cell is a wall — never
  advected, never sorted, never displaced (the generalized E4 solid guard). Terrain stays put.
- `liquid` and `fluid` → **movable, and identical to the engine.** Their only difference is the **render**
  (Java picks the block via `representative_block`). The engine governs both purely by
  `min_mass`/`max_mass`/`molar_mass`/`viscosity`.

> **Open question for you:** the third value is named `fluid`, which collides with our umbrella term "fluid =
> liquid-or-gas". I've documented it as "the gas/airy render state" (air, steam = `fluid`; water, lava =
> `liquid`). If you'd rather call the gas-like render state `gas`, say so and I'll use `{solid, liquid, gas}`.
> Either way the engine treats the two movable states identically — this is a naming choice only.

### What's wrong with the current data (the refactor checklist)

1. `min_flow_mass` → **rename to `min_mass`** (and drop the `Material.maxMass()` "0 means fall back to
   default" hack — `min_mass`/`max_mass` are always explicit).
2. `min_target`/`max_target` hold **block** ids (`minecraft:ice`, `minecraft:water`) → must be **material**
   ids (`orge:ice`, `orge:water`); create the missing target materials.
3. `state` enum `{SOLID, FLUID, GAS, ENTITY, AIR}` → **`{solid, liquid, fluid}`**; remap (water `fluid`→
   `liquid`; steam/air `gas`→`fluid`); add `state` to solids (currently absent, e.g. `generic_solid.json`).
4. `default_temp` and `pinned` missing on most files → present on every material.
5. Remove every field not in the table above; add every field that is.

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
  change to the canonical schema below: the engine LUT carries only the physics numbers
  (`thermal_conductivity`, `heat_capacity`, `molar_mass`, `min_mass`, `max_mass`, `viscosity`) plus a single
  **movable/solid** bit derived from `state`. The old boolean `fluid`/`gas`/`air` flags are **removed** — they
  no longer exist in the data or the LUT.

**REBUILD (`advect_chunk` internals → section-agnostic unified-fluid passes):**

The current `advect_chunk` is a zoo of section-local passes (`fall`, `2`/spread, `2a''`/horizontal-gas-
displace, `2c`/buoyancy-swap, `2c-seam`, `2c-seam-air`, `2d`/volume-fill) each gated on `gasFlag` and
`% SECTION_EDGE`. **Replace the entire body with two section-agnostic passes over the full column + loaded
neighbours, both driven off one pre-step `WorldSnapshot`:**

- **Pass A — molar-mass sort (gravity).** For every vertical neighbour pair across the *entire* column height
  (and within the World, so a column is one contiguous medium), if the upper cell's `molarMass` exceeds the
  lower cell's, move mass downward toward sorted order (full swap when both are full; partial otherwise).
  One cell per step; iterates to fully sorted over steps. No section skip. This single pass is *both* "fall"
  and "buoyancy".
- **Pass B — relax toward `min`, cap at `max`.** For every cell above its `min`, distribute the surplus to
  the 6-dir accessible neighbours (across all boundaries) that are below their fill, bringing cells toward
  `min`; when boxed in, mass compresses up to `max` and no further. Same-species merges; a different-species
  neighbour is displaced via the sort (Pass A), preserving one-species-per-cell. Viscosity sets the transfer
  fraction.

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

1. **Compressibility is per-fluid `min`..`max`, engine-agnostic.** No "liquid"/"gas" category in the engine.
2. **One species per cell** — displacement relocates whole, never blends.
3. **Sort by `molarMass` only** — compression never changes float order.
4. **Settle deadband + molar-sort hysteresis** — relax/swap only past a small ε (no eternal micro-flow).
5. **Sections/chunks are storage only** — physics is boundary-agnostic, full-column + loaded-neighbour.
6. **Void = lightest fluid** (`0/0/0`) — unifies vacuum/air/void into the one fluid continuum.
7. **Material data conforms to the canonical schema** above — exactly those fields; `min_mass` not
   `min_flow_mass`; targets are material ids; `state ∈ {solid, liquid, fluid}`; engine reads `state` only for
   the solid/immovable gate. Refactoring the existing (wrong) data is in scope.

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

---

## Out of scope (banked)

- Pressure/EOS continuous solver (this stays a discrete cellular relaxation; `min`/`max` approximate the
  equation of state).
- Mixtures / dissolved gas (one species per cell, decided above).
- Surface tension, temperature-driven phase change physics (phase change stays in the Java `PhaseChanger`).
- Y-band column trimming perf optimization (full-height columns for now).
