# Engine B — Velocity-Field Unified Fluid Engine — design spec

**Date:** 2026-06-04
**Status:** **RATIFIED** (§9 decisions settled with the user 2026-06-04). Ready for implementation
planning (staged, TDD, subagent-driven).
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

Engine B replaces the relaxation passes with **one momentum-driven advection + a local weakly-compressible
pressure**. The same/different branch, the molar "sort pass", the leveling pass, and the displacement
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

**What Engine B CHANGES (ratified):**

- **ΔL2 — motion gains state.** A cell's *material* is still the same handful of numbers, but its
  *motion* is no longer fully described by them: Engine B adds a **velocity vector** `v = (vx,vy,vz)` as
  **dynamic per-cell state** that persists across ticks. This is the user's "final vector": it tells
  where the cell's contents flow and how much.
- **L1 is RESTORED, not weakened.** Engine A *claims* L1 but violates it with the B/B' species branch.
  Engine B makes L1 literally true: the per-face flux rule is identical for every pair of cells; species
  only changes the *commit* (merge vs relabel), never the *decision* of amount/direction.
- **L6 (one species per cell) is KEPT** — decision §9.A = **A1 sharp interface**. A cell holds exactly one
  substance (mass + material + velocity); the velocity field advects a sharp label.

---

## §2 — Physical model (the user's vector model, refined to real physics)

Engine B is a per-cell **force balance → velocity vector** model — Newton's second law for a fluid parcel
(the Cauchy / Navier–Stokes momentum equation), discretised on the existing voxel grid and applied
**identically on all three axes, to every species, with no branch**. Governing per-cell quantities:

- **mass** `m` (kg) of the cell's one substance,
- **velocity** `v = (vx,vy,vz)` (m/s) — NEW, persistent,
- **temperature** `T` (unchanged; conduction stays in `orge_kernel.hpp`, untouched, runs as its own pass).

**Density is mass-based (§9.C):** `ρ = m / V_cell`.

### 2.1 The forces (one rule, every axis, every species)

| Force | Physics | In the engine |
|---|---|---|
| **Buoyancy** (gravity folded in) | density-difference body force | `a_buoy = g·(ρ_cell − ρ_neighbor)/ρ_cell` — vertical only; ~0 across a horizontal face. Recovers L3 molar sort as the vertical steady state; a lighter fluid can **never** push a heavier one by construction. |
| **Pressure** (temperature-coupled EOS) | `−∇p` drives flow high→low | weakly-compressible EOS in `m` and `T` (§2.2). Gives leveling, gas fill, hydrostatic head, displacement, and thermal convection — **all from one term**. |
| **Advected momentum** | fluid carries its own momentum | `a_advect = (Σ mass_in·v_donor − Σ mass_out·v_self)/m` — the inertia Engine A structurally lacks (enables sloshing). |
| **External** | hook for forces from outside the engine | `a_ext = f_ext/m` — entities, explosions, etc. |
| **Viscosity** | momentum dissipation | a **drag denominator** (§2.3), not a force term: `÷(1 + dt·μ/m)`. `μ→∞`/absent ⇒ `v→0` (frozen, skip). |

> **Why gravity, "molar bias", and "heat bias" are ONE term, not three.** Raw gravity pulls every cell
> down by `m·g`; buoyancy is the surrounding fluid pushing back. Counting both separately double-counts
> gravity. The exact combination is the single density-difference term `g·(ρ_cell − ρ_neighbor)`. Heat
> does **not** enter here (heating changes neither `m` nor `V`, so `ρ=m/V` is unchanged) — heat enters
> **only** through the pressure EOS (§2.2), which is what actually makes hot fluid rise.

### 2.2 The equation of state (temperature-coupled, derived from existing fields)

Engine B uses the material's existing `min_mass`, `default_mass`, `max_mass` fields. `default_mass` is the
**comfortable density at 1 atm** ⇒ zero gauge pressure there. Mass above it = overpressure (push out);
below it = wants to expand. **Gas-vs-liquid is a continuous property read from where `default_mass` sits**
— no `if gas` branch:

```
χ      = (max_mass − default_mass) / (max_mass − min_mass)      ∈ [0,1]   # 0 = liquid … 1 = gas
m_rest = default_mass · (1 − α·χ·(T − T_ref)/T_ref)            # clamp to (min_mass, max_mass); heat lowers it
p(m,T) = m ≥ m_rest ?  K·((m − m_rest)/(max_mass − m_rest))^γ   # compression → stiff wall at max_mass
                    : −K·χ·((m_rest − m)/(m_rest − min_mass))   # expansion → toward min_mass, χ-scaled
```

- **Liquid (χ≈0):** stiff under compression; ~0 pressure below `m_rest` ⇒ **free surface**, no suction,
  won't stretch to fill a ceiling. Pools and levels under gravity. `m_rest` barely shifts with heat (weak
  thermal expansion).
- **Gas (χ≈1):** strongly negative pressure below `m_rest` ⇒ **actively expands toward `min_mass`**, fills
  any vacuum/void. `m_rest` drops sharply with heat (ideal-gas `ρ ∝ 1/T`, linearized).
- **Hydrostatic head emerges:** gravity over-fills lower cells → `m > m_rest` → pressure resists →
  equilibrium where `∇p` balances gravity = hydrostatic. No separate leveling pass.
- **Thermal convection emerges (the chain):** `T↑ → m_rest↓ → m > m_rest → p>0 → expands, mass leaves →
  ρ=m/V↓ → buoyancy lifts it → rises`. Temperature lives only in pressure ⇒ **no double-counting** with
  buoyancy. (Convection has a natural lag — physically honest. Fallback if too sluggish: an explicit
  Boussinesq term `g·β·(T_cell−T_neighbor)`, added **only** if needed, since it risks double-counting.)

Globals (not material fields): `K` (stiffness), `γ ≥ 2` (compression ramp), `α ≈ 1` (thermal-expansion
scale), `T_ref` (reference temperature).

### 2.3 The unified per-cell velocity update (identical code, all axes)

```
ρ        = m / V_cell
a_buoy   = g·(ρ_cell − ρ_neighbor)/ρ_cell          # vertical only; ~0 horizontally
a_press  = −(1/ρ)·∇p                               # all axes; p from §2.2
a_advect = (Σ mass_in·v_donor − Σ mass_out·v_self)/m
a_ext    = f_ext/m
v_new    = (v_old + dt·(a_buoy + a_press + a_advect + a_ext)) / (1 + dt·μ/m)
```

The **drag denominator** is the user's "÷ viscosity", made time-correct:

- At rest (`v_new=v_old`) it solves to **`v = a_drive·m/μ`** — the user's "force ÷ viscosity" as the
  equilibrium.
- `μ→∞` (or absent) ⇒ denominator → ∞ ⇒ **`v→0`** — frozen solid; the cell is **skipped** (L2).
- small `μ` (water) ⇒ keeps most of `v_old` + the new impulse ⇒ **momentum persists across ticks**
  (sloshing/inertia — impossible in Engine A).

Use the **implicit** form (`/(1 + dt·μ/m)`): unconditionally stable, and frozen at `μ→∞` by construction.

---

## §3 — Cell state & material model

**Material (unchanged schema, L2):** `heat_capacity, thermal_conductivity, molar_mass, min_mass,
max_mass, viscosity` (+ the existing `default_mass`). No new material fields are required. Roles in
Engine B:

- `min_mass / default_mass / max_mass` — feed the EOS (§2.2): expansion floor, 1-atm rest point, hard
  compression wall. Their relative positions define χ (gas↔liquid spectrum).
- `molar_mass` — characteristic full-cell density / sort rank (consistent with `ρ=m/V` when full).
- `viscosity` — the **drag coefficient** in §2.3 (`+∞`/absent ⇒ frozen, `v≡0`).

**Per-cell dynamic state (NEW, persisted in SectionStore):**

- `v = (vx, vy, vz)` — `float16` per axis (storage: §6). The only state addition.

**Persistence:** velocity must survive save/load (a sloshing pool mid-motion). It is added to the
SectionStore region format as a new channel, defaulting to `0` on load of an old region (a resting start,
conservation-neutral). This is the only storage-format change.

---

## §4 — The unified per-step algorithm (ONE calculation)

Per `orgeStepWorld(world, dt)` — every cell, identical code, no species/state branch. **No global solve;
all stencils are local** (§9.B = local weakly-compressible).

### 4.1 Compute pressure (local)
For each cell: `p = p(m, T)` per §2.2 from a single pre-step snapshot. Pure local read of material fields
+ `m` + `T`.

### 4.2 Update velocity → `v_new` (local stencil)
Apply §2.3: `a_buoy + a_press + a_advect + a_ext`, then the viscous-drag denominator. Buoyancy reads the
vertical neighbor density; `a_press` reads the 6-neighbor pressure gradient; `a_advect` reads neighbor
velocities + the snapshot mass fluxes. Frozen cells (`μ→∞`/absent) ⇒ `v_new=0`, skip. **Free-slip walls
(§9.D):** terrain faces impose zero normal flux but do **not** zero tangential velocity (no boundary
drag — spread rate is governed solely by `μ`).

### 4.3 Advect mass, species, momentum, enthalpy along `v_new`
Flux-form, antisymmetric, single-snapshot (L7): each face moves `ρ·(v_new·n)·dt·area`, carrying the
donor's species + temperature + momentum. **Identical for same/different species** — only the commit
differs:
- receiver same species ⇒ **merge** (add mass, mix enthalpy/momentum),
- receiver different species ⇒ **relabel** the displaced amount (L6 sharp).

This single step subsumes Pass B (leveling = pressure-driven flux), Pass B' (cross-species displacement =
the *same* flux with a relabel commit), and the molar sort (vertical buoyant flux).

### 4.4 Hard `max_mass` wall + commit + conserve
The §2.2 stiff ramp keeps cells off the wall almost always; as the conservation backstop, the commit
**capacity-clamps** every face flux so no receiver exceeds `max_mass` (`flux = min(planned,
free_capacity_of_receiver)`, donor keeps the remainder — antisymmetric, mass-conserving). The donor's
velocity component **normal to a blocked face is damped and redirected** (the bounce/splash — the user's
"carry the vector instead of letting it flow in"). Below `min_mass`, a cell converts to **void** (L5) and
the substance consolidates. Write back mass/species/velocity/enthalpy; the region-wide conservation
backstop (Java §9) stays as the safety net.

> **Note on "law C" (one sweep per JNI call).** Engine A's scheduler does one advection sweep per call,
> `dt` scaling *amount* not *distance*. Engine B keeps this: one velocity-update + one advect per call.
> Long-range incompressible motion (vessels equalising, deep displacement) propagates through the local
> pressure gradient over successive steps — gradual, but stable, fully local, and GPU-portable (§9.B
> tradeoff, §6).

---

## §5 — Conservation & stability

- **Mass:** flux-form + antisymmetric single-snapshot + capacity clamp ⇒ each species conserved to FP;
  backstop reconciler unchanged.
- **Momentum:** advected conservatively; buoyancy + pressure + drag are momentum-stable; blocked-face
  reflection conserves (redirects, never creates) momentum up to boundary conditions (free-slip walls,
  §9.D).
- **Energy:** enthalpy rides the mass flux (as today); conduction kernel untouched and runs as its own
  pass.
- **Stability:** the implicit drag denominator is unconditionally stable; the weakly-compressible EOS is
  bounded by the stiff wall + capacity clamp; the body force needs `dt` within a buoyancy/acoustic CFL —
  clamp as the scheduler already clamps `dt∈[0.25,0.5]`, and bound `K` so the EOS sound speed respects it.

---

## §6 — Performance & GPU portability (the "performance-friendly" requirement)

- **Everything is a local stencil** — buoyancy, pressure (local EOS), advection, drag are all 6-neighbor
  reads. **No global pressure solve, no serial DFS, no global claim buffer, no variable-length chains.**
  Engine B is **structurally more GPU-portable than the spec's original projection lean** (§9.B): one
  pass, fixed stencil, embarrassingly parallel.
- **Per the compute-topology design:** the per-cell stencil (force + advect) is the GPU-worker workload on
  the client; the server runs the CPU fallback + scheduling.
- **Storage cost:** `+3×float16/cell` for velocity (6 bytes/cell). (LBM's 19 floats/cell was rejected in
  §9.A as too heavy.)
- **The §9.B tradeoff (accepted):** without a global solve, communicating vessels equalise *gradually*
  (pressure walks ~one cell/step) rather than instantly. For Minecraft this reads as natural. If
  equalisation is ever too slow, a multigrid pressure accelerator can be bolted on later **without**
  changing the model.

---

## §7 — Integration with ORGE orchestration (what stays)

- **Conduction (`orge_kernel.hpp`) is untouched** — advection never touches it (standing rule). Engine B
  replaces only `advect_world`.
- **JNI ABI:** add a velocity channel (`vx,vy,vz`) to the marshalled arrays; the resident material LUT,
  `orgeStepWorld` signature, and `PASS_CONDUCTION`/`PASS_ADVECTION` flags stay. (Velocity in/out is the
  one ABI growth.)
- **Scheduler:** unchanged (snapshot → bg step → validate → write). `dt` handling unchanged; per-World
  cadence clock retained.
- **SectionStore:** +1 velocity channel in the region format (defaults to 0 on old-region load).
- **Phase change, placement injection, material registry:** unchanged seams. Placement injects a cell
  with `v=0`; the EOS + advection naturally accommodate the new occupant.

---

## §8 — Migration from Engine A

- **Deleted:** `pass_a_sort`, `pass_b_relax`, `pass_bprime_displace`, the DFS displacement chain,
  `compute_overburden` / `O_ss`, the frontier-distance BFS, the per-donor budget, the same/different
  species branch. (All fold into §4.)
- **Reused:** `World`/`Chunk`/`MaterialLUT`, the snapshot machinery, the resident LUT, the JNI/scheduler
  scaffolding, conduction, the test harness, the Java reconciler backstop, the `min/default/max_mass`
  fields.
- **Added:** the per-cell velocity channel (state + storage + JNI), the temperature-coupled EOS, the
  unified velocity-update stencil, the capacity-clamp/reflection commit.
- **Parity strategy:** Engine B will NOT be bit-identical to A (different model). Validation is by
  *physical* acceptance tests (§10), not golden parity. Keep A on `main` until B passes the in-game gate.

---

## §9 — DECISIONS (RATIFIED 2026-06-04 with the user)

- **§9.A — Interface representation → A1: sharp, keep L6.** One species + mass + velocity per cell; the
  velocity field advects a sharp label; relabel on cross-species displacement. (Rejected: A2 volume-of-
  fluid — breaks L6/rendering; A3 LBM — ~19 floats/cell too heavy, weak at 1000:1.2 density ratio.)
- **§9.B — Solver core → local weakly-compressible vector solver.** No global pressure projection. The
  temperature-coupled EOS (§2.2) provides incompressibility locally (stiff wall) and propagates
  displacement/equalisation cell-by-cell. Chosen over the global MAC/projection for **maximum GPU
  portability**; the gradual-equalisation tradeoff is accepted (§6) and re-acceleratable later.
- **§9.C — Density → mass-based**, `ρ = m / V_cell` (couples to fill level and compression; `molar_mass`
  is the full-cell rank).
- **§9.D — Wall boundary → free-slip.** Zero normal flux, no tangential drag; spread rate set by `μ`.
  (No-slip rejected: sub-cell boundary layer at 1m voxels ⇒ artificial drag, double-damps with viscosity.)
- **§9.E — Air compressibility → weakly-compressible, temperature-coupled** (§2.2). Yields gas fill,
  pressure relief, and thermal convection. (Temperature pulled into pressure **from stage 1**, per user —
  without it, "hot rises" cannot occur given `ρ=m/V`.)

---

## §10 — Validation / acceptance tests (physical, not golden)

1. **Flat-surface rest:** a placed water cell spreads to `floor(m/min)` tiles and **stops** (no wander) —
   the defect that started this.
2. **Communicating vessels:** single-driver U-tube self-levels; multi-arm/manometer **equalises**
   (gradually, via local pressure — the banked Engine-A limitation B fixes by construction).
3. **Buoyancy ordering:** lava < water < air resting order; a light fluid never displaces a heavy one
   laterally (defect #1) — assert by construction.
4. **Incompressible displacement:** inject lava into a full water pocket; water rises elsewhere, mass
   conserved, **no chain**; no receiver ever exceeds `max_mass`.
5. **Sloshing/inertia:** tilt a filled basin (or remove a wall); water oscillates and settles — proves
   the velocity field carries momentum (impossible in Engine A).
6. **Gas fill:** a gas (χ≈1) released into vacuum/void spreads to fill, thinning toward `min_mass`; a
   liquid (χ≈0) does **not** fill a ceiling (free surface).
7. **Thermal convection:** heat a gas column from below; it over-pressures, thins, and rises; a cooler
   column sinks — a convection cell forms. Liquid convects weakly. (Validates the temperature-coupled EOS
   chain, §2.2.)
8. **Conservation soak:** thousands of steps, per-species mass invariant; velocity bounded (no blow-up).
9. **Performance:** per-tick cost vs Engine A at fixed loaded-region size.

---

## §11 — Risks

- **Gradual equalisation** (the §9.B tradeoff) — communicating vessels level over many steps; mitigated
  by it looking natural in Minecraft and by a later optional multigrid accelerator (no model change).
- **EOS tuning** — `K`, `γ`, `α`, `T_ref` must be tuned so leveling is brisk, the wall is stiff but
  stable, and convection is visible but not explosive. Bound `K` by the `dt` CFL (§5).
- **Numerical diffusion of the sharp interface (A1)** — advecting a label smears; needs an
  anti-diffusion/interface-sharpening step or a level-set if smearing is visible.
- **Storage growth** — the mandatory velocity channel (`+6 bytes/cell`) in regions/save format.
- **Scope:** this is a real solver, not a refactor. Stage it (force+advect+EOS first, hard-wall/commit
  second, interface sharpening + thermal convection tuning third) behind acceptance tests; keep Engine A
  shipping on `main` throughout.

---

*Next: writing-plans → staged, TDD, subagent-driven implementation on this `rebuild` track.*
