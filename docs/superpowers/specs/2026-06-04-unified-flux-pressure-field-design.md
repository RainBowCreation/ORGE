# Unified Flux + Jacobi Pressure Field — Engine A "A-unify" design

**Date:** 2026-06-04
**Track:** A-unify (main worktree `/home/claude/ORGE`, parent `main`, engine submodule `main`)
**Status:** APPROVED design (brainstormed with user 2026-06-04). Next: implementation plan.
**Supersedes the *advection model* of:** `2026-06-01-unified-fluid-engine-design.md` (laws unchanged),
`2026-06-04-unified-fluid-math-design.md` (hydrostatic head — this generalizes it),
`2026-06-03-chained-displacement-train.md` (the DFS chain — this dissolves it).

---

## STOP — non-negotiable laws (do not re-break)

These are the Engine A design laws. This redesign exists to make **L1 literally true**; it must not
weaken any of the others.

- **L1 — one identical calculation.** EVERY cell/face runs the SAME flow calc. No same/different-species
  branch, no gas/liquid/solid branch, no `gasFlag`/`fluidFlag`/`state`. Species is *cargo*.
- **L2 — immovability is data.** `viscosity == +INF` (absent) ⇒ frozen. `movable()` is the only motion gate.
- **L3 — gravity is part of the flow, not a separate `fall` pass.** (This redesign folds the old molar-sort
  Pass A *into* the unified flux — see §4.)
- **L4 — sections/chunks are STORAGE ONLY.** No `% SECTION_EDGE`, no seam pass, no halo. Walk full columns
  `0..CHUNK_H-1` and across loaded chunks.
- **L5 — void = lightest fluid** (molar 0).
- **L6 — one species per cell.**
- **L7 — conservation by antisymmetric single-snapshot transfers + the Java region-ledger backstop.**

**Scope boundary:** this is still **quasi-static density relaxation. NO velocity/momentum field** — that is
Engine B (`rebuild` worktree). The pressure field `p` here is a *density-consistency* pressure (hydrostatic +
incompressibility), not a momentum projection. It never stores or advects velocity.

---

## 1. Why (the L1 violation we are removing)

Today advection runs **three** passes that each special-case species:

- **Pass A** (`pass_a_sort`) — gravity: molar-mass full-cell swap + same-species vertical compaction.
- **Pass B** (`pass_b_relax`) — same-species leveling + vacuum-fill, on a **multi-source BFS frontier-distance
  field**, with a per-donor outflow budget and atomic-`min_mass` budding. Hydrostatic head via full `O`.
- **Pass B'** (`pass_bprime_displace`) — cross-species displacement via a **serial DFS push-chain**, plus the
  same-species communicating-vessels root driven by `O_ss`.

The split into "same species → level / different species → displace" is exactly the branch L1 forbids. Real
flow has no such branch: pressure drives mass, and species is just what rides along. The branch is also the
source of the most red-teamed bugs in the engine (lava/water churn, mass-from-nothing, flat-pool drift) and
the two **GPU-hostile** structures (irregular BFS + serial DFS).

**Real compute lives on the client GPU** (DESIGN §3). The engine math must be GPU-portable stencils. BFS and
DFS are pointer-chasing and irregular — the opposite of a stencil. This redesign replaces them.

---

## 2. The unified model (one field, one flux, one commit)

### 2.1 One pressure field `p` (replaces `O`, `O_ss`, and the DFS chain)

`p` is a single scalar field over all cells, found by **K fixed Jacobi iterations per step**, warm-started
from the previous step's `p` (temporal coherence makes few iterations enough):

```
for k in 1..K:
    p_i ← (Σ_neighbour movable p) / n_movable_neighbours  +  source_i
    p_i ← 0   at every FREE SURFACE  (a cell facing vacuum/air across a movable face)   // Dirichlet BC
source_i = ρ_i · g            (gravity head; ρ_i from molarMass)            // hydrostatic term
         + κ · max(0, mass_i − max_i)   (overpack)                          // incompressibility term
```

Properties (why this one field does everything):

- **Static column** → relaxes to `p = ρg·depth`, i.e. exactly today's hydrostatic `O`. The +Y flood-guard
  arithmetic (`pi − pj == mass_i` on a calm column ⇒ zero drive) is reproduced by construction.
- **U-tube / communicating vessels, ANY number of arms** → `p` equalizes across the bottom connection, so
  the taller arm drives the shorter one up. The **banked multi-arm/manometer gap**
  (`[[orge-communicating-vessels-gap]]`, `[[orge-task6-redteam-findings]]`) is closed *for free* — same solver,
  no special code, no one-directional pump/overshoot.
- **Inject into a full incompressible pocket** → the overpack source spikes `p` locally; `∇p` pushes the
  surplus to the nearest free surface over K iterations. That is the DFS push-chain, expressed as a stencil.

### 2.2 One flux per face (species is cargo)

For each of a cell's three positive faces (i ↔ neighbour j), **one identical expression**:

```
Φ_i = max(0, mass_i − min_i)            // local compression (donatable surplus)
    + p_i                               // transmitted pressure / head
drive(i→j) = (Φ_i − Φ_j)
           + buoyancy(i,j)              // VERTICAL faces only; ∝ (ρ_above − ρ_below), 0 unless inverted
dm = clamp( rate(visc_donor) · dt · drive ,  floor: donor ≥ min_i ,  cap: receiver ≤ max_j )
```

`rate(visc)·dt` is the existing time-based throttle (Law C / `2026-06-02`): viscosity scales the **amount**,
never the rule. Donor floor (`min`) and receiver cap (`max`) preserve the occupancy model.

### 2.3 The ONLY species-dependent line — the commit

```
if same species:        merge   — receiver mass += dm, enthalpy-mix T
else (different):        relabel — move dm AND exchange species labels (L6: one species per cell)
```

This is the whole "same/different" distinction L1 allows: it is not a *branch in the physics*, it is how the
moved cargo is written. Direction, amount, and the decision to move are identical for every face.

### 2.4 Conservation is structural and independent of `p`'s accuracy

Every transfer is **antisymmetric**: donor `−dm` / receiver `+dm`, mass and enthalpy. `p` only sets
**direction and rate**; a half-converged `p` (few Jacobi iterations) can shift *how fast* the system settles
but can **never create or destroy mass**. This is what makes fixed-K iteration safe (§5) and keeps L7 intact.
The Java region-ledger HOLD remains the outer backstop.

---

## 3. Frontier concentration via `∇p` (the BFS dies)

A free pool has `p = 0` at its rim (free surface BC) and `p > 0` in its interior, so **`∇p` already points
outward toward the frontier** — the exact directionality the multi-source BFS hand-computed. Budding a new
occupied tile becomes part of the same flux:

- Where `∇p` drives mass across a face into vacuum/air (a frontier face), deposit the existing **atomic
  `min_mass` dose** under the existing single-donor claim. No BFS distance field, no per-donor budget hack.

**Required property (validation gate, §7):** a **flat settled pool produces zero horizontal `∇p`** (no creep),
while an **un-spread lump produces outward `∇p`** (spreads to `floor(M/min)` tiles, not `floor(M/2·min)`). The
free-surface Dirichlet BC should give exactly this; it must be proven, not assumed.

---

## 4. Gravity folds into the flux (no separate Pass A)

The old molar-mass full-cell swap becomes the **buoyancy term on vertical faces** + the **relabel commit**:

- A vertical face with `ρ_above > ρ_below` (inverted, unstable) gets a positive `buoyancy(i,j)`; the flux
  moves dm and the relabel commit exchanges species → heavy sinks, light rises. One cell per step via the
  existing one-swap claim, so descent stays one cell/step (deterministic convergence).
- A **stable** column (`ρ_above ≤ ρ_below`) gets `buoyancy = 0` and hydrostatically-balanced `Φ` ⇒ **zero
  drive** ⇒ no churn. This is the structural fix for the stable-stratification-churn trap: a settled
  arrangement has no downhill move, so it cannot oscillate (lava/water equilibrium holds by construction, not
  by a bolt-on density gate).
- Same-species vertical **compaction** (fill the lower cell to `max`) is just the `Φ` compression term on the
  +Y face with the merge commit — no special case.

---

## 5. Law-C reconciliation (bounded work, no death-spiral)

Law C (shipped 2026-06-04) says: **one sweep per call, `dt` scales amount not distance, no sub-cycle.** A
Poisson solve is iterative, which superficially conflicts. Reconciliation:

- The **flux** stays **one sweep per call** (one antisymmetric stencil pass). Unchanged.
- The **pressure relaxation** is **K fixed Jacobi iterations** (K a small constant, e.g. 4–8), **not
  iterate-to-convergence**. Bounded work per call; warm-started from last step's `p` so it converges over
  multiple steps via temporal coherence. No death-spiral: `p` accuracy never gates correctness (conservation
  is structural, §2.4) — an under-converged `p` just settles a little slower.
- K is the single perf knob. It is **per-World** state alongside `p` (mirrors the per-World cadence clock).

---

## 6. What is deleted vs. added

**Deleted:** `pass_a_sort`, `pass_b_relax`, `pass_bprime_displace`, `compute_overburden`,
`compute_overburden_samespecies`, the frontier-distance multi-source BFS, the per-donor outflow budget, the
serial DFS push-chain (`ChainNode`/`MAX_CHAIN_DEPTH`), the `bprimeRot` direction de-bias, the explicit
density-only cross-species gate (`if (!(mJ.molarMass < mI.molarMass)) continue;` — implicit in buoyancy now).

**Added:** a per-World pressure field `p` (+ warm-start storage), a `relax_pressure()` Jacobi kernel (K
iterations, free-surface BC), and one `advect_unified()` flux stencil (Φ + buoyancy, merge/relabel commit,
atomic-dose budding, one-swap & single-donor claims retained).

**Unchanged:** `orge_kernel.hpp` (conduction only — advection never touches it), the 6-float `Material`
struct and the 6-array/resident-LUT JNI ABI, the whole-World `orgeStepWorld` orchestration and the scheduler
cadence, `rate()/advanced()/dt` time-base.

---

## 7. Behaviours that MUST be preserved (regression list)

Each is an existing guarded behaviour the unified model must reproduce — these become the test oracles:

1. Water poured onto a floor spreads to **exactly `floor(M/min)` tiles** (not `floor(M/2·min)`), through
   vacuum AND through `orge:air`, across chunk seams.
2. A flat settled pool **does not drift/creep** in any horizontal direction (no `bprimeRot`-style bias).
3. **Lava/water equilibrium** is quiet — no churn, no oscillation (the stable-stratification trap).
4. **Single-driver U-tube self-levels**; a flat pool never spontaneously climbs.
5. **Multi-arm U-tube equalizes** (NEW — previously banked; this is the win, must be tested).
6. **No mass from nothing**: water → N cells sums to EXACTLY the input mass (oracle `== 1000.0` class tests).
7. Inject lava into a full water pocket → water relocates to a remote free surface; lava displaces water
   **up** where appropriate (the old DFS case), now via `∇p`.
8. Per-species conservation across every step; `:core:integrationTest` **skipped=0** on the real `.so`.

---

## 8. GPU shape (the point of all this)

Two textbook-GPU kernels, no irregular control flow:

1. **`relax_pressure`** — K Jacobi iterations. Each iteration is a local 6-neighbour stencil (read neighbour
   `p`, write `p`), plus a free-surface mask. Embarrassingly parallel per cell; K is a fixed loop. (Multigrid
   is a later optimization; plain Jacobi is the portable baseline.)
2. **`advect_unified`** — one local 6-neighbour flux stencil reading `Φ`, antisymmetric write (red-black or
   atomic to resolve the write race). Per-cell parallel.

No BFS, no DFS, no prefix-scan-with-reset, no serial column dependency. CPU server fallback runs the same two
kernels as plain loops.

---

## 9. Open validation questions (carry into the plan as explicit checks)

- **Flat-pool zero-creep (§3):** prove `∇p` is horizontally zero on a settled flat pool under the free-surface
  BC. If creep appears, fall back to a temporally-amortized min-plus frontier field *alongside* `p` (the
  rejected option) — design the code so this is a localized swap, not a rewrite.
- **K selection:** smallest K that holds behaviours §7.1–7.7 without visible lag in-game. Start K≈4, tune.
- **Buoyancy term calibration (§4):** the vertical buoyancy coefficient must reproduce one-cell-per-step
  descent and not over/under-drive against the `Φ` compression term.
- **Behaviour parity vs today:** A-unify need NOT be bit-identical (it changes the model). Where an existing
  test encodes the *old* two-pass behaviour, fix the test to the unified behaviour **with user sign-off**.

---

## 10. Validation gate

- Engine C++ suite green: `cd ORGE-ENGINE && ./tests/run_tests.sh` (iterate), `... quick` before commit.
  **Never `full`** (hours-long stress).
- Rebuild `.so`, then Java gate:
  `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest` — **`integrationTest` skipped=0**
  (proves the real `.so` loaded). Both loaders: `:fabric-1.21:build :neoforge-1.21:build`.
- Push engine `main` + bump parent gitlink + push parent `main` after every commit (`[[always-push-rebuild]]`).
- **Final gate: in-game audit** (the headless suites have repeatedly missed live-air bugs).
