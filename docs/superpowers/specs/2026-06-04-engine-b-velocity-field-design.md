# Engine B — Velocity-Field Unified Fluid Engine — design spec

**Date:** 2026-06-04
**Status:** DRAFT, pre-implementation. **Decisions §9 must be settled before any code.**
**Track:** parent `rebuild` ↔ engine `rebuild` (worktree `/home/claude/ORGE-B`). Engine A (the
relaxation model) continues on `main` as the *A-unify* refactor; Engine B is the from-the-ground-up
velocity-field successor. They are developed in parallel; whichever proves out in-game wins.

---

## §0 — Why Engine B exists (root cause, not symptoms)

Two in-game defects, same root:

1. **Light fluid moves heavy fluid.** A churning air column laterally displaced water on flat ground
   ("random wander"). 1 kg of air cannot relocate 125 kg of water — momentum says ~0.008 m/s. *(Stopgap
   shipped on `main`/engine `e8999ea`: cross-species displacement gated to strict density. It treats the
   symptom.)*

2. **The species split is not physics.** The engine has `Pass B` (same-species + vacuum leveling) and
   `Pass B'` (cross-species displacement) as separate algorithms. Real fluid flow has **no** "same vs
   different species" branch — there is one momentum equation; species is a label advected by the flow.

**Both are consequences of two missing things in Engine A:**

- **No velocity field.** Engine A is *quasi-static relaxation*: each tick it recomputes a pressure
  gradient and nudges mass downhill. There is no momentum carried tick-to-tick, so "where and how much
  flows" has no single source of truth — it is re-derived per pass, differently for same/different
  species. The user's insight: *flow should be defined by the cell's velocity vector* — one vector,
  one rule, any species.
- **Displacement-at-a-distance done by a serial DFS chain.** Because a cell holds one incompressible
  substance with no momentum, relocating water out of the way of injected lava requires a variable-length
  serial search (`down → hash4 → up`) to a remote sink. That chain is the GPU-hostile holdout and the
  thing that makes the model branch on species.

Engine B replaces the relaxation passes with **one momentum-driven advection + an incompressibility
projection**. The same/different branch, the molar "sort pass", the leveling pass, and the displacement
chain all collapse into a single per-cell stencil.

---

## §1 — Design laws (carried over) and what Engine B changes

**Non-negotiable, inherited verbatim from the Engine A spec (still true):**

- **L1. One substance, one identical calculation.** No `gas`/`liquid`/`solid` concept, no `state` field,
  no fluid/solid filter. Java hands the engine the whole chunk unfiltered, exactly like conduction.
- **L2. Immovability is data, not a branch.** `viscosity` absent ⇒ frozen (`+∞` ⇒ flow rate 0). The test
  `movable ⟺ flow-rate > 0` replaces every fluid/solid branch. Sand/gravel = finite viscosity with
  `min_mass ≈ max_mass`.
- **L3. Gravity is buoyancy by density, not a "fall" rule.** Heavier sinks, lighter rises. "Falling" is a
  heavy fluid sorting below the lightest fluid (void/air).
- **L4. Sections & chunks are storage ONLY.** No `% SECTION_EDGE`, no seam/halo. Every pass walks the full
  column across all loaded chunks as one continuous medium.
- **L5. Void is the lightest fluid** (`molar=min=max=0`, finite viscosity), not a special case.
- **L7. Conservation by construction + backstop.** Antisymmetric flux from a single pre-step snapshot;
  region-wide reconciliation backstop in Java (§9 of the orchestration).

**What Engine B CHANGES (and must be ratified):**

- **ΔL2 — motion gains state.** A cell's *material* is still the same handful of numbers, but its
  *motion* is no longer fully described by them: Engine B adds a **velocity vector** `v = (vx,vy,vz)`
  (and, depending on §9.A, distribution functions) as **dynamic per-cell state** that persists across
  ticks. This is the user's "final vector": it tells where the cell's contents flow and how much.
- **L1 is RESTORED, not weakened.** Engine A *claims* L1 but violates it with the B/B' species branch.
  Engine B makes L1 literally true: the per-face flux rule is identical for every pair of cells; species
  only changes the *commit* (merge vs relabel), never the *decision* of amount/direction.
- **L6 (one species per cell) is UNDER REVIEW** — see decision §9.A. A velocity field advecting a sharp
  interface either keeps L6 (sharp, needs interface handling) or relaxes it at interfaces (volume
  fractions). This is the single biggest fork.

---

## §2 — Physical model

Engine B simulates **variable-density, low-speed fluid flow with buoyancy**, discretised on the existing
voxel grid. The governing quantities per cell:

- **mass** `m` (kg) of the cell's substance (as today),
- **velocity** `v` (m/s) — NEW, persistent,
- **temperature/enthalpy** (unchanged; conduction stays in `orge_kernel.hpp`, untouched).

Forces, all expressed as contributions to the momentum update — **one rule, every species:**

| Term | Physics | In the engine |
|---|---|---|
| **Pressure** | `−∇p` drives flow high→low | per-face pressure difference (incl. hydrostatic head) |
| **Gravity/buoyancy** | density-dependent body force | `g·(ρ_cell − ρ_ref)` ⇒ recovers L3 molar sort as the *vertical* steady state |
| **Viscosity** | momentum diffusion | `viscosity` smooths `v` between neighbours (recovers the cadence/ooze) |
| **Advection** | fluid carries its own momentum + mass + species | semi-Lagrangian or flux-form transport along `v` |
| **Incompressibility** | `∇·v = 0` for a full liquid | pressure projection (§4.3) — **replaces the DFS chain** |

Buoyancy from `g·(ρ_cell − ρ_neighbour)` is the key unifier: it makes "heavy sinks / light rises"
(L3, today's molar sort) and "a denser fluid displaces a lighter one" (today's Pass B') the *same* term —
density difference in the body force — so a lighter fluid can **never** push a heavier one (defect #1
dies by construction, not by a gate). Lateral motion comes only from real pressure gradients and inertia.

---

## §3 — Cell state & material model

**Material (unchanged schema, L2):** `heat_capacity, thermal_conductivity, molar_mass, min_mass,
max_mass, viscosity`. `molar_mass` now feeds the buoyancy body force (density `ρ ≈ molar_mass · n`, or
`ρ = m / cell_volume` — see §9.C). `viscosity` becomes the momentum-diffusion coefficient (and `+∞`
still ⇒ frozen, `v ≡ 0`). No new material fields required for the baseline.

**Per-cell dynamic state (NEW, persisted in SectionStore):**

- `v = (vx, vy, vz)` — fixed-point or `float16`/`float32` per axis (storage cost: §6).
- *(If §9.A picks volume-of-fluid)* a fractional fill `φ ∈ [0,1]` at interface cells.
- *(If §9.A picks LBM)* distribution functions `f_i` (D3Q19 ⇒ 19 floats/cell) — large; see §6/§9.A.

**Persistence:** velocity must survive save/load (a sloshing pool mid-motion). It is added to the
SectionStore region format as a new channel, defaulting to `0` on load of an old region (a resting
start, conservation-neutral). This is the only storage-format change.

---

## §4 — The unified per-step algorithm (ONE calculation)

Per `orgeStepWorld(world, dt)` — every cell, identical code, no species/state branch:

### 4.1 Apply body forces → tentative velocity `v*`
For each cell: `v* = v + dt·( g·(ρ_cell − ρ_up)/ρ_cell  −  drag(viscosity)·v )`. Frozen cells: `v* = 0`.
This is a pure local stencil. Buoyancy here *is* gravity (L3); no separate fall/sort pass.

### 4.2 Advect mass, species, momentum along `v*`
Flux-form, antisymmetric, single-snapshot (L7): each face moves `ρ·(v*·n)·dt·area`, carrying the donor's
species + temperature + momentum. **Identical for same/different species** — the only difference is the
commit:
- receiver same species ⇒ **merge** (add mass, mix enthalpy/momentum),
- receiver different species ⇒ **relabel** the displaced amount (L6 sharp) **or** blend the fraction φ
  (§9.A volume-of-fluid).

This single step subsumes Pass B (leveling = pressure-driven flux), Pass B' (cross-species displacement =
the *same* flux with a relabel commit), and the molar sort (vertical buoyant flux).

### 4.3 Pressure projection — incompressibility (the chain replacement)
For the incompressible part (full liquid: `m ≈ max_mass`), enforce `∇·v ≈ 0` by solving for a pressure
correction and subtracting `∇p`:
`∇²p = (ρ/dt)·∇·v*`, then `v = v* − (dt/ρ)·∇p`.
This is what lets injected lava push a *whole connected* water body up a far arm **without** a serial DFS
chain — the pressure field propagates the displacement globally in one solve. Solver = iterative
**Jacobi / red-black SOR / multigrid**, all local stencils ⇒ GPU-portable (§6). Compressible cells
(air, `min<max` wide) use a weakly-compressible equation of state instead of a hard constraint, so the
solve only tightens where the fluid is actually incompressible.

### 4.4 Commit + conserve
Write back mass/species/velocity/enthalpy; the region-wide conservation backstop (Java §9) stays as the
safety net. Per-species mass invariant holds by antisymmetric flux.

> **Note on "law C" (one sweep per JNI call).** Engine A's scheduler does one advection sweep per call,
> `dt` scaling *amount* not *distance*. Engine B's projection propagates displacement globally per solve,
> so a single call already resolves long-range incompressible motion — law C is preserved *and* the
> "deep displacement crawls one cell/step" worry from the A-unify path does not apply here.

---

## §5 — Conservation & stability

- **Mass:** flux-form + antisymmetric single-snapshot ⇒ each species conserved to FP; backstop reconciler
  unchanged.
- **Momentum:** advected conservatively; body force + projection are momentum-conserving up to boundary
  conditions (walls = no-slip or free-slip, §9.D).
- **Energy:** enthalpy rides the mass flux (as today); conduction kernel untouched and runs as its own
  pass.
- **Stability:** semi-Lagrangian advection is unconditionally stable; the projection is stable; the body
  force needs `dt` within a buoyancy CFL — clamp as the scheduler already clamps `dt∈[0.25,0.5]`.

---

## §6 — Performance & GPU portability (the "performance-friendly" requirement)

- **Everything is a local stencil** except the pressure solve, which is *iterative* local stencils. No
  serial DFS, no global claim buffer, no variable-length chains — Engine B is **structurally more
  GPU-portable than Engine A** (it removes the one GPU-hostile pass).
- **Per the compute-topology design:** the heavy per-cell stencils (force, advect, each projection
  iteration) are the GPU-worker workload on the client; the server runs the CPU fallback + scheduling.
- **Storage cost** is the main tax: `+3 floats/cell` for velocity (×`float16` ⇒ 6 bytes/cell). LBM
  (§9.A) would add 19 floats/cell — likely **too heavy** for a full Minecraft world; this is a strong
  argument for the MAC/projection core over LBM.
- **Projection iteration budget** is the per-tick cost knob: bounded iterations (e.g. 20–40 Jacobi or a
  2–3 level multigrid V-cycle) trade exactness for speed; residual-gated early-out keeps quiescent
  regions cheap.

---

## §7 — Integration with ORGE orchestration (what stays)

- **Conduction (`orge_kernel.hpp`) is untouched** — advection never touches it (standing rule). Engine B
  replaces only `advect_world`.
- **JNI ABI:** add a velocity channel to the marshalled arrays; the resident material LUT, `orgeStepWorld`
  signature, and `PASS_CONDUCTION`/`PASS_ADVECTION` flags stay. (Velocity in/out is the one ABI growth.)
- **Scheduler:** unchanged (snapshot → bg step → validate → write). `dt` handling unchanged.
- **SectionStore:** +1 velocity channel in the region format (defaults to 0 on old-region load).
- **Phase change, placement injection, material registry:** unchanged seams. Placement injects a cell
  with `v=0`; the projection naturally accommodates the new occupant.

---

## §8 — Migration from Engine A

- **Deleted:** `pass_a_sort`, `pass_b_relax`, `pass_bprime_displace`, the DFS chain, `compute_overburden`
  / `O_ss`, the frontier-distance BFS, the per-donor budget. (All of it folds into §4.)
- **Reused:** `World`/`Chunk`/`MaterialLUT`, the snapshot machinery, the resident LUT, the JNI/scheduler
  scaffolding, conduction, the test harness, the Java reconciler backstop.
- **Parity strategy:** Engine B will NOT be bit-identical to A (different model). Validation is by
  *physical* acceptance tests (§10), not golden parity. Keep A on `main` until B passes the in-game gate.

---

## §9 — DECISIONS NEEDED (settle before code)

**§9.A — Interface representation (biggest fork).**
- **(A1) Sharp interface, keep L6** (one species/cell): advect the label; reconstruct the interface to
  fight numerical diffusion (PLIC/VOF-sharp or a level-set). Honors the existing law; more code at the
  interface; crisp water/air boundary like vanilla.
- **(A2) Volume-of-fluid fractions** (relax L6 at interfaces): a cell may be e.g. 0.4 water / 0.6 air.
  Simpler advection, naturally handles partial fill / thin films; but breaks "one species per cell" and
  changes rendering/`representative_block`.
- **(A3) Lattice-Boltzmann (LBM):** velocity emerges from distribution functions; very GPU-native and
  local. **But:** 19 floats/cell storage, and large density ratios (water:air ≈ 1000:1.2) are a known
  LBM weakness needing advanced multiphase models. Likely too memory-heavy for full worlds.

> My honest lean (stated, not imposed): **A1 (sharp, keep L6) over a MAC/projection core.** It preserves
> your design laws, keeps memory cheap (3 floats/cell), keeps the crisp block-fluid look, and the
> projection cleanly replaces the chain. LBM is the most "real velocity" but the storage + density-ratio
> cost fights the performance goal on a full world.

**§9.B — Solver core:** MAC-grid + pressure projection (recommended, pairs with A1) vs LBM (A3) vs a
weakly-compressible-only scheme (no projection — cheapest, least exact for incompressible displacement).

**§9.C — Density definition:** `ρ = m / cell_volume` (mass-based, dynamic) vs `ρ ∝ molar_mass` (fixed per
species). Mass-based couples to compression; molar-based is simpler and matches today's sort key.

**§9.D — Wall boundary condition:** free-slip (fluid glides along terrain) vs no-slip (sticks). Affects
how water sheets across floors.

**§9.E — Compressibility of air:** weakly-compressible EOS vs treat air as incompressible-with-low-density.
Determines whether you get pressure waves / "air pressure" (the thing the user asked about originally).

---

## §10 — Validation / acceptance tests (physical, not golden)

1. **Flat-surface rest:** a placed water cell spreads to `floor(m/min)` tiles and **stops** (no wander) —
   the defect that started this.
2. **Communicating vessels:** single-driver U-tube self-levels; multi-arm/manometer **equalises** (the
   banked Engine-A limitation B should fix for free via the global projection).
3. **Buoyancy ordering:** lava < water < air resting order; a light fluid never displaces a heavy one
   laterally (defect #1) — assert by construction.
4. **Incompressible displacement:** inject lava into a full water pocket; water rises elsewhere, mass
   conserved, **no chain**.
5. **Sloshing/inertia:** tilt a filled basin (or remove a wall); water oscillates and settles — proves the
   velocity field carries momentum (impossible in Engine A).
6. **Conservation soak:** thousands of steps, per-species mass invariant; velocity bounded (no blow-up).
7. **Performance:** per-tick cost vs Engine A at fixed loaded-region size; projection iteration budget
   sweep.

---

## §11 — Risks

- **Projection cost** on large loaded regions — mitigated by bounded iterations + residual early-out +
  GPU offload; still the main perf risk.
- **Numerical diffusion of the interface** (A1) — needs interface reconstruction or it smears.
- **Large density ratios** destabilising the projection (variable-density Poisson) — needs care.
- **Storage growth** in regions/save format — velocity channel is mandatory; LBM (A3) likely prohibitive.
- **Scope:** this is a real solver, not a refactor. Stage it (force+advect first, projection second,
  interface sharpening third) behind acceptance tests; keep Engine A shipping on `main` throughout.

---

*Next: ratify §9 (esp. §9.A/§9.B), then write the implementation plan (staged, TDD, subagent-driven) on
this `rebuild` track.*
