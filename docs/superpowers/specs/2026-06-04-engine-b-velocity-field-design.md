# Engine B — Unified Energy-Flux Fluid+Thermal Engine — design spec

**Date:** 2026-06-04
**Status:** **RATIFIED** (§9 decisions settled with the user 2026-06-04). Ready for implementation
planning (staged, TDD, subagent-driven).
**Track:** parent `rebuild` ↔ engine `rebuild` (worktree `/home/claude/ORGE-B`). Engine A (the
relaxation model) continues on `main` as the *A-unify* refactor; Engine B is the from-the-ground-up
successor. They are developed in parallel; whichever proves out in-game wins.

> **The one-sentence model:** every cell, in parallel, computes a single directed **energy-flux vector
> `J_E`** ("energy moving per time") from its **own state only**; a resolve pass moves energy between cells
> reading **only each other's `J_E`** (never raw mass/temperature/density); flow, pressure, buoyancy,
> cell-swaps, convection, **and heat conduction** all emerge as **energy taking the lowest-energy path.**

---

## §0 — Why Engine B exists (root cause, not symptoms)

Two in-game defects, same root:

1. **Light fluid moves heavy fluid.** A churning air column laterally displaced water on flat ground
   ("random wander"). 1 kg of air cannot relocate 125 kg of water. *(Stopgap shipped on `main`/engine
   `e8999ea`: cross-species displacement gated to strict density. It treats the symptom.)*

2. **The species split is not physics.** Engine A has `Pass B` (same-species leveling) and `Pass B'`
   (cross-species displacement) as separate algorithms. Real transport has **no** "same vs different
   species" branch — there is one conservation law; species is a label carried by the flux.

**Both are consequences of Engine A having no carried state and re-deriving transport per pass, branching
on species, and doing displacement-at-a-distance via a GPU-hostile serial DFS chain.** The user's
insight, refined across this brainstorm: transport should be defined by **one carried per-cell vector** —
and that vector is most honestly an **energy flux**, because energy is the universal currency that unifies
motion, pressure, buoyancy, and heat into a single quantity.

Engine B replaces *all* of Engine A's transport (Pass A sort / Pass B relax / Pass B' displace / DFS chain
/ molar sort) **and** the separate conduction pass with **one unified energy-flux step**.

---

## §1 — Design laws

**Inherited verbatim from Engine A (still true):**

- **L1. One substance, one identical calculation.** No `gas`/`liquid`/`solid` concept, no `state` field,
  no fluid/solid filter. Java hands the engine the whole chunk unfiltered.
- **L2. Immovability is data, not a branch.** `viscosity` absent ⇒ frozen (`+∞` ⇒ flow rate 0). The test
  `movable ⟺ flow-rate > 0` replaces every fluid/solid branch. Sand/gravel = finite viscosity with
  `min_mass ≈ max_mass`.
- **L3. Gravity is buoyancy by density, not a "fall" rule.** Heavier sinks, lighter rises — emergent, not
  a swap pass (§4.4).
- **L4. Sections & chunks are storage ONLY.** No `% SECTION_EDGE`, no seam/halo. Every pass walks the full
  column across all loaded chunks as one continuous medium.
- **L5. Void is the lightest fluid** (`molar=min=max=0`, finite viscosity), not a special case.
- **L7. Conservation by construction + backstop.** Antisymmetric flux from a single pre-step snapshot;
  region-wide reconciliation backstop in Java. **Engine B conserves ENERGY and MASS** (mass rides the
  advective energy flux).

**Changed / NEW in Engine B (ratified):**

- **ΔL2 — transport gains carried state: the energy-flux vector.** A cell's *material* is still the same
  handful of numbers, but its *transport* is now carried as **dynamic per-cell state**: a velocity vector
  `v` (the kinetic part of `J_E`, persistent across ticks — gives inertia/sloshing). The full `J_E` is
  recomputed each step from `{m, v, T, material}`.
- **L1 RESTORED.** The per-face energy-flux rule is identical for every pair of cells; species only
  changes the *commit* (merge vs relabel/swap), never the *decision*.
- **L6 KEPT (§9.A = A1 sharp):** one species + mass + velocity + temperature per cell.
- **L8 NEW — conduction is unified (§9.F = Option B).** The standing "advection never touches
  `orge_kernel.hpp` conduction" rule is **lifted for Engine B.** Heat conduction is the *diffusive channel*
  of `J_E`; there is no separate conduction pass. (Engine A on `main` keeps the split.)
- **L9 NEW — cells share ONLY `J_E`.** No cell ever reads a neighbor's raw mass, temperature, density, or
  EOS. The only inter-cell quantity is the energy-flux vector. Everything else is own-state, used to
  *generate* `J_E` (Pass 1) and to *unpack* a received `J_E` into Δmass/Δvelocity/Δtemperature (Pass 2).

---

## §2 — Physical model: the total energy flux

The shared per-cell quantity is the **total energy flux** of continuum mechanics — a real, complete object
that by construction contains every transport channel:

```
J_E  =  ρ(e + ½v² + g·z)·v   +   p·v   +   q
         │      │      │            │        │
      internal kinetic gravit.   pressure  conductive
      (thermal)(motion)potential   work    heat (diffusive)
```

Each term maps to a phenomenon the engine must produce:

| Channel | Resolves to |
|---|---|
| `ρ·½v²·v` (kinetic) | **velocity / momentum / inertia / sloshing** |
| `ρ·g·z·v` (gravitational) | **falling, buoyancy, density sorting** (heavier ⇒ more downward PE flux) |
| `p·v` (pressure work) | **leveling, incompressibility, the hard wall** |
| `ρ·e·v` (internal, advected) | **convective heat** (heat carried by moving mass) |
| `q` (diffusive) | **heat conduction** (Fourier, in relaxation form — §4.5) |
| `·v` on the advective terms | **mass movement** (mass carries all advective energy) |
| net energy-lowering exchange | **cell swap** (two cells trade contents when it lowers total energy) |

Velocity is *one projection* of `J_E` (the kinetic term); capping the shared quantity to velocity would
drop pressure, buoyancy, and heat. Energy is the whole thing.

### 2.1 Two channels of flux: advective (mass-carrying) vs diffusive (mass-free)

- **Advective flux** moves at the fluid's velocity `v` and **carries mass** (and with it kinetic,
  gravitational, internal, and pressure-work energy). This is flow, buoyancy, displacement, swaps,
  convection.
- **Diffusive flux** `q` is **mass-free** energy transport — conduction. It is the unified replacement for
  `orge_kernel.hpp` (§4.5, L8).

The commit (§4.3) splits a received `J_E` by these two carriers: the advective part changes
mass+momentum+enthalpy; the diffusive part changes enthalpy only.

### 2.2 The equation of state (feeds the pressure-work channel; temperature-coupled)

`J_E`'s pressure term needs a per-cell pressure `p`. Engine B uses the material's existing `min_mass`,
`default_mass`, `max_mass`. `default_mass` = comfortable density at 1 atm ⇒ zero gauge pressure. Gas-vs-
liquid is a **continuous property read from data** (no branch):

```
χ      = (max_mass − default_mass) / (max_mass − min_mass)      ∈ [0,1]   # 0 = liquid … 1 = gas
m_rest = default_mass · (1 − α·χ·(T − T_ref)/T_ref)            # clamp (min,max); heat lowers it
p(m,T) = m ≥ m_rest ?  K·((m − m_rest)/(max_mass − m_rest))^γ   # compression → stiff wall at max_mass
                    : −K·χ·((m_rest − m)/(m_rest − min_mass))   # expansion → toward min_mass, χ-scaled
```

- **Liquid (χ≈0):** stiff under compression; ~0 pressure below `m_rest` ⇒ free surface, won't fill a
  ceiling. `m_rest` barely shifts with heat.
- **Gas (χ≈1):** strongly negative below `m_rest` ⇒ actively expands toward `min_mass`; `m_rest` drops
  sharply with heat (ideal-gas `ρ ∝ 1/T`, linearized) ⇒ thermal convection emerges.
- **Hydrostatic head** emerges (gravity over-fills lower cells → `p` resists → equilibrium = hydrostatic).
- Globals (not material fields): `K` (stiffness), `γ ≥ 2`, `α ≈ 1`, `T_ref`.

### 2.3 Gravity is uniform; buoyancy is emergent (no neighbor density read)

Gravitational *acceleration* is `g` for every cell (`a=F/m=g`, mass cancels) — Pass 1 adds it with no
neighbor read. **Buoyancy is not a density comparison;** it emerges in the resolve: a denser cell carries
more downward gravitational + kinetic energy flux and, on hitting an incompressible neighbor or floor,
**reflects** that energy sideways/up, carrying lighter fluid up. Heavy ends low, light ends high, with no
cell ever reading another's density (L9).

### 2.4 Absorb vs reflect (incompressibility, from own EOS)

How a cell responds to an incoming energy flux is set by its **own** EOS:

- **Compressible cell** (own χ≈1): **absorbs** the flux — it compresses, so the flow passes through it.
- **Incompressible cell** (own χ≈0): **reflects** the flux — it bounces the push back/sideways (its `p`
  shoots up the stiff `^γ` ramp).

The neighbor never announces "I'm full/dense"; you learn it because next step its `J_E` either kept going
(absorbed) or bounced back (reflected). **Pressure is transduced into the energy flux**, never shared as a
raw value.

---

## §3 — Cell state & material model

**Material (unchanged schema, L2):** `heat_capacity, thermal_conductivity, molar_mass, min_mass,
default_mass, max_mass, viscosity`. **No new material fields.** Roles in Engine B:

- `min/default/max_mass` — EOS (§2.2): expansion floor, 1-atm rest, hard wall; positions define χ.
- `molar_mass` — characteristic full-cell density / sort rank (consistent with `ρ=m/V` when full).
- `viscosity` — **drag coefficient** for the kinetic channel (`+∞`/absent ⇒ frozen, `v≡0`).
- `thermal_conductivity` — coefficient of the diffusive channel `q` (§4.5).
- `heat_capacity` — unpacks received internal energy into ΔT.

**Per-cell dynamic state, persisted in SectionStore:**

- `v = (vx, vy, vz)` — the carried kinetic part of `J_E` (`float16`/axis). **NEW persisted channel.**
- `T` (temperature / internal energy) — already persisted.
- `m`, species — already persisted.

`J_E` itself is *derived* each step from `{m, v, T, material}`; only `v` is the new thing to persist
(defaults to 0 on old-region load — a resting start, conservation-neutral).

---

## §4 — The unified per-step algorithm (TWO passes, ONE calculation)

Per `orgeStepWorld(world, dt)`. **No global solve; all stencils local.** **No species/state branch.**

### 4.1 Pass 1 — parallel, per cell, OWN STATE ONLY → emit `J_E`
For every cell, from `{m, v, T, material}` and gravity (uniform `g`), compute the cell's energy-flux
vector `J_E` (§2): the advective channels (kinetic from `v`, gravitational from `g`, internal from `T`,
pressure-work from `p(m,T)`) plus the diffusive channel `q` from `thermal_conductivity`, all damped by the
viscous drag (kinetic channel only). **Zero neighbor reads.** Frozen cells (`viscosity →∞`/absent):
kinetic/advective channels = 0 (they can still conduct via `q`). This pass is embarrassingly parallel —
pure map, GPU-ideal.

### 4.2 Pass 2 — resolve, reads ONLY neighbors' `J_E`
For each face, compute the **antisymmetric** net energy flux from the two cells' `J_E` (single pre-step
snapshot, L7): `F_ij = −F_ji`. Energy flows down the energy gradient (second law). The face flux splits
into:
- **advective part** — carries mass at the donor's velocity (donor uses its **own** density for the mass
  amount); brings the donor's species + enthalpy + momentum;
- **diffusive part** — mass-free conduction (§4.5).

**Absorb/reflect (§2.4)** is applied here: an incompressible receiver's stiff `p` makes its `J_E` oppose
the inflow ⇒ the donor's advective momentum is **reflected** (redirected to open faces) instead of
overfilling. **Free-slip walls (§9.D):** terrain faces pass no advective flux and impose no tangential
drag.

### 4.3 Commit + conserve (energy AND mass)
Each cell unpacks its net received/sent `J_E` into its **own** state using its **own** material:
- advective in/out ⇒ Δmass (+species: **merge** if same species, **relabel/swap** if different, L6 sharp),
  Δmomentum ⇒ Δ`v` (`v` persists), Δinternal ⇒ ΔT via `heat_capacity`;
- diffusive in/out ⇒ ΔT only.
Energy is conserved by the antisymmetric flux; mass is conserved because it rides the advective energy;
the Java reconciler stays as the hard backstop. As a conservation backstop the advective commit
capacity-limits so no receiver grossly exceeds `max_mass` (the stiff `^γ` ramp keeps it off the wall
almost always; transient overshoot is relaxed next step by `p`).

### 4.4 Vertical sorting / displacement is EMERGENT — no sort pass, no threshold, no surface tension
Density sorting, displacement, tube-pinning, and bubbles are **not** separate steps or value comparisons.
They fall out of §4.1–§4.3 (gravity + absorb/reflect + the energy flux):
- **Compressible displaced fluid yields:** water sinks through *air* (air absorbs/compresses), tube
  `[W,A,A] → [A,A,W]`.
- **Incompressible + no room pins** ("balls in a tube can't pass"): lava on *water* in a closed 1-cell
  tube reflects → `[L,A,W] → [A,L,W]` and stays. The stiff-but-finite wall ⇒ an extreme drive can still
  creep through (the "swap if force is large enough" exception) — automatic.
- **Pool ⇒ bubbles, emergent:** with lateral room, reflected energy redirects sideways → circulation →
  the heavier fluid descends in **plumes/bubbles** (one cell already reads as a bubble; pillow-lava free).

**Implementation guard:** there must be **no** `if(denser_above) swap()` pass and **no** density-threshold
test. Order is whatever the energy flux settles to.

### 4.5 Conduction is the diffusive channel `q` (unified, L8) — no temperature sharing
There is **no separate conduction pass.** A cell emits a diffusive energy flux `q` from its **own** thermal
energy and `thermal_conductivity` (Pass 1). At a face, the net diffusive flux is the difference of the two
cells' emitted `q` ⇒ energy flows hot→cold **without sharing temperature** (the emitted `q` encodes `T`
via own state) — Fourier conduction in relaxation form. The receiver unpacks it into ΔT via its own
`heat_capacity`. Pure conduction (no flow) is recovered when the advective channels are zero. *(This
replaces `orge_kernel.hpp`; see §7/§8.)*

> **Note on "law C" (one sweep per call).** One Pass-1 + one Pass-2 per `orgeStepWorld` call; `dt` scales
> amount, not distance. Long-range equilibration (vessels, deep displacement, heat soak) propagates
> through the local energy flux over successive steps — gradual, stable, fully local, GPU-portable.

---

## §5 — Conservation & stability

- **Energy:** antisymmetric single-snapshot flux ⇒ total energy conserved to FP; energy cannot be created
  ⇒ no blow-ups.
- **Mass:** rides the advective energy flux; conserved per species; capacity-limited commit + Java
  reconciler backstop.
- **Momentum:** carried in `v`; reflection redirects (never creates) momentum, up to BCs (free-slip).
- **Stability:** implicit viscous drag is unconditionally stable; the weakly-compressible EOS is bounded
  by the stiff wall; bound `K`/conduction rate within the `dt` CFL (scheduler clamps `dt∈[0.25,0.5]`).

---

## §6 — Performance & GPU portability

- **Two local stencils** (Pass 1 = pure per-cell map; Pass 2 = fixed 6-neighbour energy-flux). **No global
  solve, no serial DFS, no claim buffer, no chains, no separate conduction pass.** Structurally GPU-ideal.
- **One unified step** replaces both advection *and* conduction (fewer passes than Engine A overall).
- **Storage cost:** `+3×float16/cell` for `v` (6 bytes/cell). Temperature already stored.
- **Tradeoff (accepted):** relaxation ⇒ equilibration (vessels, heat) is **gradual** (energy walks ~one
  cell/step). Natural-looking in Minecraft; a multigrid accelerator can be added later without model
  change.

---

## §7 — Integration with ORGE orchestration

- **Conduction kernel SUBSUMED (the big change, L8).** `orge_kernel.hpp`'s conduction becomes the
  diffusive channel of `J_E`; Engine B owns heat + flow in one step. (Engine A on `main` is untouched.)
- **JNI ABI:** add the velocity channel (`vx,vy,vz`) to the marshalled arrays; temperature already
  marshalled. Resident material LUT and the `orgeStepWorld` signature stay; the `PASS_CONDUCTION`/
  `PASS_ADVECTION` flag split collapses into one unified step (flags retained as no-ops / for A-parity).
- **Scheduler:** unchanged shape (snapshot → bg step → validate → write). Because conduction is now in the
  same step, the separate heat cadence folds in; `dt` handling and the per-World cadence clock stay.
- **SectionStore:** +1 velocity channel (defaults 0 on old-region load).
- **Phase change, placement injection, material registry:** unchanged seams. Placement injects a cell with
  `v=0`; the energy flux accommodates it.

---

## §8 — Migration from Engine A

- **Deleted:** `pass_a_sort`, `pass_b_relax`, `pass_bprime_displace`, the DFS chain, `compute_overburden`/
  `O_ss`, the frontier BFS, the per-donor budget, the same/different species branch, **and the separate
  conduction pass** (folded into `q`).
- **Reused:** `World`/`Chunk`/`MaterialLUT`, snapshot machinery, resident LUT, JNI/scheduler scaffolding,
  the test harness, the Java reconciler backstop, the `min/default/max_mass`/`thermal_*` fields.
- **Added:** the per-cell velocity channel (state+storage+JNI), the `J_E` formulation, the two-pass
  energy-flux step, absorb/reflect commit, the unified diffusive conduction channel.
- **Parity strategy:** Engine B is NOT bit-identical to A (different model). Validation is by *physical*
  acceptance tests (§10). Keep A on `main` until B passes the in-game gate. Conduction parity vs the old
  kernel is a *physical* equilibration check (§10.10), not golden.

---

## §9 — DECISIONS (RATIFIED 2026-06-04 with the user)

- **§9.A — Interface → A1 sharp, keep L6.** One species + mass + velocity + temperature per cell.
- **§9.B — Solver core → local energy-flux relaxation, NO global projection.** The EOS provides
  incompressibility locally; equalisation propagates cell-by-cell. Chosen for **maximum GPU portability**;
  the gradual-equalisation tradeoff is accepted (§6).
- **§9.C — Density → mass-based**, `ρ = m / V_cell`.
- **§9.D — Wall boundary → free-slip.**
- **§9.E — Air compressibility → weakly-compressible, temperature-coupled EOS** (§2.2); temperature is in
  pressure from stage 1.
- **§9.F — Conduction → UNIFIED (Option B).** Heat conduction is the diffusive channel of `J_E`; the
  separate `orge_kernel.hpp` conduction pass is subsumed (L8). The standing "conduction untouched" rule is
  lifted for Engine B only.
- **§9.G — Shared inter-cell quantity → the total ENERGY-FLUX vector `J_E`** (not velocity). Velocity is
  its kinetic projection. Cells share only `J_E` (L9); raw mass/temp/density never cross a cell boundary.

---

## §10 — Validation / acceptance tests (physical, not golden)

1. **Flat-surface rest:** a placed water cell spreads to `floor(m/min)` tiles and **stops** (no wander).
2. **Communicating vessels:** single-driver U-tube self-levels; multi-arm/manometer **equalises**
   (gradually, via local energy flux).
3. **Buoyancy ordering (bulk):** in an open pool, lava/water/air settle to lava < water < air; a light
   fluid never displaces a heavy one laterally. **No sort pass exists** (§4.4).
4. **Tube pinning (emergent):** closed 1-cell tube. `[W,A,A] → [A,A,W]` (water through compressible air);
   `[L,A,W] → [A,L,W]` and **stays** (lava pinned on incompressible water). No density-threshold path.
5. **Bubble / plume (emergent):** light fluid released at the bottom of a water *pool* rises as rounded
   blobs via circulation; lava on a pool descends in plumes.
6. **Incompressible displacement:** inject lava into a full water pocket; water rises elsewhere, mass +
   energy conserved, **no chain**.
7. **Sloshing/inertia:** tilt a filled basin; water oscillates and settles — proves `v` carries momentum.
8. **Gas fill:** a gas (χ≈1) released into vacuum/void fills, thinning toward `min_mass`; a liquid (χ≈0)
   does **not** fill a ceiling.
9. **Thermal convection:** heat a gas column from below; it over-pressures, thins, rises; a cooler column
   sinks — a convection cell forms (validates the EOS chain + advective heat).
10. **Pure conduction (unified `q`):** a static temperature gradient through immovable material relaxes to
    equilibrium and matches the Fourier solution (validates L8 — the conduction channel replacing the
    kernel) with **no mass motion**.
11. **Conservation soak:** thousands of steps — total **energy** and per-species **mass** invariant; `v`
    bounded; no blow-up.
12. **Performance:** per-tick cost of the unified step vs Engine A (advection + conduction combined).

---

## §11 — Risks

- **Scope (largest):** Engine B now subsumes conduction (L8) — it replaces *two* Engine A subsystems. Stage
  it carefully (see below); keep Engine A shipping on `main` throughout.
- **Energy decomposition:** splitting a received `J_E` into mass-carrying vs mass-free (and into
  Δv/ΔT/Δm) needs a clean, conservative rule — the core implementation risk.
- **Gradual equalisation** (the §9.B/§9.F tradeoff): vessels level and heat soaks over many steps;
  mitigated by it looking natural + an optional later multigrid accelerator.
- **EOS / conduction tuning:** `K, γ, α, T_ref`, conduction rate must be tuned for brisk-but-stable
  behavior; bound by the `dt` CFL.
- **Numerical diffusion of the sharp interface (A1):** advecting a label smears; needs an
  anti-diffusion/interface-sharpening step if visible.
- **Storage growth:** the mandatory `v` channel (`+6 bytes/cell`).

**Staging (writing-plans will detail):**
1. **Energy-flux core, mechanical only** — Pass 1/Pass 2, kinetic+gravitational+pressure channels;
   acceptance tests 1,3,4,6,8.
2. **Inertia + emergent sort/bubbles + reflection** — momentum persistence, absorb/reflect; tests 2,5,7.
3. **Thermal unification** — internal/advective + diffusive `q` channels (L8, subsume the kernel); tests
   9,10,11.
4. **Tune + interface sharpening + perf** — test 12, anti-diffusion, multigrid only if needed.

---

*Next: writing-plans → staged, TDD, subagent-driven implementation on this `rebuild` track.*
