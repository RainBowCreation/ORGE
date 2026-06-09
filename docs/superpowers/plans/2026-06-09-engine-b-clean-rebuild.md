# Engine-B Clean Rebuild — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended)
> or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> **Read [`../DESIGN-LAW.md`](../DESIGN-LAW.md) FIRST, verbatim.** It is frozen and outranks this plan, the
> old spec, and all code. If anything here contradicts it, the law wins — stop and flag it.

> **⚠ LAW AMENDMENTS (2026-06-09, ratified by the user) — these SUPERSEDE the stage text below on conflict.**
> The law gained points 7–9 and revised 1/2/4 *after* this plan was drafted. Honor these deltas:
> - **State (law §7): store EXTENSIVE, derive INTENSIVE.** Per-cell stored = `matIx, mass,
>   momentum(px,py,pz), E, P`. **Store `E` not `T`** (`T = E/(mass·cp)` derived) and **store `momentum`
>   not velocity** (`v = momentum/mass` derived). The "int16 velocity" reuse in Stage 0/2 becomes **int16
>   momentum**; Stage 4.1 (enthalpy carried) is promoted to a Stage-0 invariant, not a late add.
> - **Force / gravity / external (law §2):** the 6-face force accumulates into `momentum`. Gravity = global
>   `g` (registered with the material table at mod-load, overridable per `step_world`). **External force
>   enters as a per-cell momentum-impulse array** argument to `step_world`, added directly to momentum —
>   the Stage 7 JNI ABI must carry it.
> - **ENCODE output (law §4) is minimal:** provisional `momentum*` (`= momentum + mass·g·dt + external`),
>   cached `T`, and gas `P_eos`. Nothing else re-emitted (this kills the drifted 14-field `CellEncrypt`).
> - **Material schema (law §8):** the 8 engine fields + the phase quadruple `minTemp→minTarget,
>   maxTemp→maxTarget` all live **in the engine LUT**. `defaultMass` = **EOS rest density m₀**, NOT
>   placement mass — correct any stage text that implies otherwise (esp. Stage 5).
> - **Conservation (law §9):** there is **no `sealedLoss` deletion**. A pushed cell with no escape is a
>   **no-op** (it compresses via EOS). Add a `no_escape` detection seam (empty body) in RESOLVE (Stage 2 /
>   Stage 6) for later use — it must default to do-nothing, never destroy.

**Goal:** Rebuild the Engine-B physics core (ENCODE→RESOLVE→DECODE) clean from the frozen law — replacing the
drifted `A+B` pressure split with **one** pressure scalar `P` computed by **iterative local relaxation** — so a
single 6-face `−∇P` Vector3 force does all mass movement and heat rides the same pipeline.

**Architecture:** A fresh physics core `core/engine_b2.hpp` built straight from the law, on top of the
**reused, non-drifted infrastructure** (World/Chunk/snapshot/LUT, `chi`/`eos`, int16 velocity quantization, the
JNI ABI). Pressure is one persisted scalar `Chunk::P`, relaxed each tick by a gravity-sourced Jacobi/red-black
pressure projection with free-surface (`P=0`) and wall (Neumann) boundary conditions — it builds the true
hydrostatic field (depth inside it, the surface cell's own head included, edges clean) so `−∇P` alone levels,
spreads, gushes-at-depth, and U-tubes. Heat carries enthalpy `E` (`T` derived); conduction is the same 6-face
flux for energy. Conservation is by antisymmetric face flux; swaps are permutations; phase change is a DECODE
relabel.

**Tech Stack:** C++20 header engine (`core/`), self-contained real-LUT C++ tests (`tests/`), JNI → NeoForge/Fabric
loaders, Gradle. No synthetic Materials in tests (real LUT only — law/spec §6).

---

## Why a *clean* core (reuse vs rebuild)

The drift was **always in the physics** (the pressure model, the swap gate, the force), never in the data
plumbing. So we rebuild the physics from the law and **reuse** what was never the problem:

| REBUILD clean from the law (`engine_b2.hpp`) | REUSE verbatim (cite + lock with a test, do NOT re-derive) |
|---|---|
| pressure solve (relaxation), force (`−∇P+g`), mass advect, swap gate, conduction wiring, phase relabel, the ENCODE/RESOLVE/DECODE shell | `World`/`Chunk`/`ChunkSnapshot`/`MaterialLUT` (`sim_engine.hpp`), `chi()`/`eos_pressure()`, donor-budget & receiver-room clamps, the conduction **max-principle clamp**, int16 velocity quantization, the JNI marshalling/ABI, `engine_b_real_lut.hpp` |

`engine_b.hpp` (the drifted core) stays in-tree as **reference only** until Stage 7 swaps the live path; it is
never copied from. `own_weight_head`, the `divU`-only `p`, and the `A+B` framing do **not** appear in
`engine_b2.hpp` — that is the whole point.

---

## The law, restated as this plan's north star (the acceptance invariants)

Every task ends by asserting one or more of these, on the **real LUT**, asserting **MOVED** (not "bounded").
**No invariant is marked done until it has been RUN against the engine** (the anti-drift rule — the old INV-1b
was backwards because it was written but never run).

- **L1 — one P:** exactly one persisted pressure buffer `Chunk::P`. No second pressure field, no
  `own_weight_head`, no direction-split. (CI grep + struct-member diff.)
- **L2 — P is the true hydrostatic field:** single supported cell `P = ρg·dx/2`; sealed column `P(depth) =
  ρg·d` **at every depth** (not just the base); free surface `P≈0`; edges clean (no horizontal `∇P` at equal
  depth in a flat pool ⇒ no edge-pumping leak).
- **L3 — one isotropic force:** force is one Vector3 `−∇P + g` from the 6 face-neighbours (face-averaged
  gradient); same rule every direction; a deep side-hole effluxes faster than a shallow one **for free**.
- **L4 — conservation:** grand mass, per-species mass, and grand energy exact by construction (antisymmetric
  flux + permutation swaps); bounds `0 ≤ m ≤ max`, never `0 < m < min` (settled), `T ∈ stencil`, `|v|dt ≤ dx`.
- **L5 — heat = same pipeline:** cell carries enthalpy `E`; `T = E/(mass·cp)` derived; conduction `−k∇T`
  6-face, energy-exact; no separate conduction pass; no stored-T-as-truth.
- **L6 — resistance:** move iff net force > resistance; `yield/cohesion` = threshold, `viscosity` = rate only.
- **L7 — one snapshot / order-invariant:** RESOLVE reads ONE pre-step snapshot, ZERO post-update reads;
  forward vs reverse iteration ⇒ bit-identical; any random pair has `Δq_i = −Δq_j`.

---

## File Structure

- **Create** `core/engine_b2.hpp` — the clean physics core: `step_world_b2()` + `encode/resolve/decode` +
  `relax_pressure()` + `resolve_forces_advect()` + `resolve_swap()` + `resolve_conduct()` + `decode_cells()`.
- **Modify** `core/sim_engine.hpp` — add `Chunk::P` (one `std::vector<float>`) if not already the single
  pressure buffer; add per-material `minTemp/minTarget/maxTemp/maxTarget` to the LUT (Stage 5).
- **Reuse** `tests/engine_b_real_lut.hpp` — the real-LUT harness (no change).
- **Create** `tests/engine_b2_*.cpp` — one test file per stage (pressure, force_advect, swap, conduct, phase,
  resistance, integration), each REAL-LUT only.
- **Modify** the JNI bridge (Stage 7) — point `orgeStepWorld` at `step_world_b2`.
- **Modify** material datapack JSON (Stage 5) — add the four phase fields.

Build/run a test: `g++ -std=c++20 -O2 -g -I. -Icore tests/engine_b2_<x>.cpp -o build/<x> -pthread && ./build/<x>`

---

## Stage 0 — Scaffold + invariant harness

**Goal:** a compiling clean core that no-op-conserves, plus the assertion helpers every later task reuses.

### Task 0.1: clean-core skeleton

**Files:** Create `core/engine_b2.hpp`; Test `tests/engine_b2_smoke.cpp`.

- [ ] **Step 1 — failing test:** empty-world step conserves and is a no-op.
```cpp
#include "engine_b_real_lut.hpp"
#include "engine_b2.hpp"
#include <cstdio>
using namespace orgeb;
int main(){ World w; rlut::RealLut M = rlut::make_real_lut(w); w.ensureChunk(0,0);
  double m0 = rlut::grand_mass(w);
  for(int t=0;t<10;++t) step_world_b2(w, w.materials, M.G, 0.5f);
  if(std::fabs(rlut::grand_mass(w)-m0) > 1e-9){ std::printf("FAIL mass\n"); return 1; }
  std::printf("engine_b2_smoke OK\n"); return 0; }
```
- [ ] **Step 2 — run, expect FAIL** (`step_world_b2` undefined). `g++ … tests/engine_b2_smoke.cpp …`
- [ ] **Step 3 — minimal core:** `engine_b2.hpp` with `inline void step_world_b2(World&, const MaterialLUT&,
  const Globals&, float dt)` that snapshots → `encode()` → `resolve()` → `decode()`, all currently no-ops that
  copy state through unchanged. Reuse `World`/`Chunk`/`ChunkSnapshot` from `sim_engine.hpp`; do NOT include
  `engine_b.hpp`.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): clean-core skeleton (encode/resolve/decode no-op shell)`

### Task 0.2: invariant assertion helpers

**Files:** Create `tests/engine_b2_invariants.hpp`; Test `tests/engine_b2_invariants_selftest.cpp`.

- [ ] **Step 1 — failing test:** helpers must FLAG a hand-broken state.
```cpp
// assert_conserved(w, m0_per_species) ; assert_bounds(w, mats) (no 0<m<min, m<=max) ;
// assert_energy(w, E0). Each returns #violations. Self-test: corrupt one cell -> expect >=1 violation;
// clean state -> 0.
```
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** the three helpers (grand + per-species mass via `rlut::species_mass`; bounds loop
  over `CHUNK_N`; energy via `Σ mass·cp·T`). Port the bound thresholds from the law (L4).
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `test(engine-b2): conservation/bounds/energy invariant helpers`

---

## Stage 1 — The clean single-`P` (iterative local relaxation) — THE CRUX

**Goal:** a persisted `Chunk::P` that **is** the true hydrostatic field (L2). Build the mechanism with a SPIKE
first and prove it against L2 before anything else is built on it. **If the spike cannot hit L2, STOP and
escalate** (the law's clean-P premise would be at risk — do not paper over it).

**Mechanism (the relaxation, one sweep per call; P persisted, converges over ticks — raise sweeps/tick K only
if convergence is too slow, that's the perf knob):**
```
ENCODE (per cell, local):  u*  = u + dt·g                       # provisional velocity, gravity only (down=-y)
RESOLVE pressure (cross-cell, Jacobi sweep over fluid cells i):
  d_i = Σ_faces (u*·n̂) · A                                      # net outflow; WALL face = 0 flux (Neumann),
                                                                #   FREE-SURFACE face (gas/vacuum neighbour) open
  # Poisson:  ∇²P = (ρ_i/dt)·d_i , with P=0 at free surface, ∂P/∂n=0 at walls
  Σnb, n = 0
  for each face f:
     if neighbour is FLUID (incompressible, occupied):  Σnb += P_nb ; n += 1
     elif neighbour is FREE SURFACE (gas/vacuum):        Σnb += 0     ; n += 1     # Dirichlet P=0
     else (WALL):                                         continue                  # Neumann: omit
  P_i_new = ( Σnb − (ρ_i/dt)·d_i·dx² ) / n
  P_i = max(0, relax·P_i_new + (1−relax)·P_i)                   # under-relax for stability (relax≈0.7)
DECODE: persist Chunk::P = P_i
```
Gas cells: `P` from `eos_pressure` (their EOS carries it); they are the `P=0` Dirichlet sink for liquids.

### Task 1.1: SPIKE — validate the relaxation hits L2

**Files:** Create `tests/engine_b2_pressure_spike.cpp` (throwaway-grade but kept as the L2 regression test).

- [ ] **Step 1 — write the L2 assertions (failing):**
```cpp
// (a) single supported WATER cell on STONE floor, air around: after K steps  P ≈ ρg·dx/2 = 5000 (±2%).
// (b) sealed WATER column H=8 on STONE: P(depth d=k·dx) ≈ ρg·d at EVERY k (top≈ρg·dx/2, base≈ρg·H), monotone.
// (c) free surface: top water cell P ≈ ρg·dx/2 (its own head), the air above P ≈ 0.
// (d) EDGES CLEAN: flat pool (same height, same mass) — max horizontal |P_i − P_j| at equal depth < 1% ρg·dx
//     (=> no edge-pumping gradient). Run 400 steps; assert it STAYS clean (no growth).
```
- [ ] **Step 2 — run, expect FAIL** (relaxation not implemented).
- [ ] **Step 3 — implement `relax_pressure()`** in `engine_b2.hpp` per the mechanism above; call it from
  `resolve()`. Persist `Chunk::P`.
- [ ] **Step 4 — run; tune to PASS.** If (a)–(d) don't all pass: adjust BC handling (the free-surface vs wall
  classification is the usual culprit), `relax`, or sweeps/tick `K`. **Record the converging values in the test
  output.** If structurally unreachable after honest effort → STOP, write a findings note, escalate (the law's
  clean-P assumption is in question — this is the gate, not a formality).
- [ ] **Step 5 — commit:** `feat(engine-b2): clean single-P via gravity-sourced relaxation (L2 spike green)`

### Task 1.2: lock L2 as permanent invariants + L1

**Files:** Create `tests/engine_b2_pressure.cpp`; Modify `core/sim_engine.hpp` (ensure single `Chunk::P`).

- [ ] **Step 1 — failing test:** the four L2 cases from 1.1 as a permanent suite + **L1**: assert (by a
  compile-time/struct check or a documented manifest test) there is exactly one pressure buffer and no
  `own_weight_head` symbol.
- [ ] **Step 2 — run, expect FAIL** (suite new).
- [ ] **Step 3 — implement:** move the spike's validated relaxation into the locked path; add the L1 manifest
  check (grep-style assertion in a comment-test or a tiny `static_assert` on the struct).
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `test(engine-b2): lock L1 (one P) + L2 (hydrostatic field) invariants`

### Task 1.3: U-tube / communicating vessels (the long-banked gap)

**Files:** add to `tests/engine_b2_pressure.cpp`.

- [ ] **Step 1 — failing test:** two WATER columns of unequal height, connected by a one-cell channel at the
  floor, STONE walls, air above both. After settling: the two surface heights are equal (±1 cell), mass exact.
  (This is the case `A+B` could never do; the clean depth-in-`P` should.)
- [ ] **Step 2 — run** (expected FAIL until force exists — this test depends on Stage 2; mark it `@stage2` and
  move it to run at the end of Task 2.3 if force isn't wired yet). *Pressure alone can't move mass; this asserts
  the field is right by checking the eventual equilibrium once force lands.*
- [ ] **Step 3/4/5:** (completed at Task 2.3 — see note there.)

---

## Stage 2 — Force (`−∇P + g` Vector3) + conservative mass advection

**Goal:** L3 + L4. The one isotropic 6-face force moves mass conservatively; leveling, spread, U-tube, and
depth-driven efflux all fall out of `−∇P` with no special-casing.

### Task 2.1: the 6-face force

**Files:** `core/engine_b2.hpp` (`resolve_forces_advect`); Test `tests/engine_b2_force_advect.cpp`.

- [ ] **Step 1 — failing test:** hydrostatic rest — a sealed settled column has net force ≈ 0 (`max‖u‖` decays
  to < 1e-3 over 500 steps); a single unsupported cell over air accelerates down (`vy < 0`, grows ~`g·dt`).
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** the force: per cell, `F = g·m·ŷ_down + Σ_faces −½(P_i+P_j)·A·n̂` (face-averaged
  pressure gradient; wall faces apply the one-sided reaction `−P_i·A·n̂`; free-surface faces relieve to `P=0`).
  Result is one Vector3 `(Fx,Fy,Fz)` → integrate to velocity `u += F/m·dt`, then `u *= (1−vel_damp)`. **Same
  rule all 6 faces** (L3).
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): one isotropic 6-face force (−∇P + g) → velocity`

### Task 2.2: conservative mass advection

**Files:** `core/engine_b2.hpp`; Test as above.

- [ ] **Step 1 — failing test:** a water cell dropped above a floor falls and **lands** — mass exact every step
  (`assert_conserved`), no cell `> max`, no fabrication, `|v|dt ≤ dx` (CFL). Forward vs reverse cell iteration
  ⇒ **bit-identical** end state (L7).
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** antisymmetric mass flux along the resolved velocity (upwind), with the **PORTED**
  donor-budget (never drain donor below 0 unless fully) + receiver-room (never exceed `max`) clamps from
  `engine_b.hpp` (cite the exact lines; these are proven, not drift). One pre-step snapshot only (L7).
- [ ] **Step 4 — run, expect PASS** (incl. the forward/reverse bit-identical check).
- [ ] **Step 5 — commit:** `feat(engine-b2): conservative antisymmetric mass advection (donor/receiver clamps)`

### Task 2.3: horizontal leveling from the ONE P (kills the A+B reason-for-existing) + close Task 1.3

**Files:** `tests/engine_b2_force_advect.cpp`, `tests/engine_b2_pressure.cpp`.

- [ ] **Step 1 — failing tests:** (i) SCENE-1 `[1000|500]` on a flat floor → `[750|750]` (±1), mass 1500 exact,
  **no air-launch** (the air above never gains upward velocity > 1e-3), driven purely by `−∇P` (no
  `own_weight_head` in the code path). (ii) Run the **Task 1.3 U-tube** test to completion: surfaces equalize.
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement:** nothing new if Stage 1+2.1+2.2 are correct — leveling is `−∇P` doing its job.
  If SCENE-1 does NOT level, the bug is in the pressure field (Stage 1), not a missing leveling channel — fix it
  there, do **not** reintroduce a horizontal-only head term (that would be re-drift; L1/L3 forbid it).
- [ ] **Step 4 — run, expect PASS** (both SCENE-1 and U-tube).
- [ ] **Step 5 — commit:** `feat(engine-b2): same-species leveling + U-tube fall out of −∇P (no A+B)`

### Task 2.4: spread into open air + dam-break

**Files:** `tests/engine_b2_force_advect.cpp`.

- [ ] **Step 1 — failing test:** an over-full puddle beside open floor-air spreads (≥6 cells, tallest << start),
  mass exact; a tall column beside air slumps (frontier advances), mass exact, bounded (no `> max`).
- [ ] **Step 2 — run, expect FAIL/PASS** (may already pass from 2.1–2.3; if so, it's a lock test).
- [ ] **Step 3 — implement** the cross-species displacement only if needed (water-in / air-out as the 6-face
  force pushes the lighter gas; gas is compressible so it takes the pressure impulse — PORT the compressible-side
  split). Keep mass per-species exact.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): pool→air spread + dam-break slump (per-species exact)`

### Task 2.5: depth-driven efflux (proves depth is IN P — the thing B could not do)

**Files:** `tests/engine_b2_force_advect.cpp`.

- [ ] **Step 1 — failing test:** a tall sealed column with a one-cell side opening at the **bottom** vs an
  identical column opened at the **top** — the bottom opening effluxes faster (peak `|vx|_bottom > |vx|_top`),
  consistent with `v ∝ √depth`. (This is the §-discussion payoff: the bottom gushes for free.)
- [ ] **Step 2 — run, expect FAIL or PASS** (if the field is right, it passes — it's a proof test).
- [ ] **Step 3 — implement:** none expected; if it fails, the depth profile in `P` is wrong → back to Stage 1.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `test(engine-b2): depth-driven efflux (deep hole gushes, depth lives in P)`

---

## Stage 3 — Buoyancy swap (immiscible reorder)

**Goal:** lava sinks under water; dense-on-light inverts; equal density holds; STONE never swaps — by density
difference vs pair resistance (L6), as a pure permutation (L4).

### Task 3.1: density-difference swap gate

**Files:** `core/engine_b2.hpp` (`resolve_swap`); Test `tests/engine_b2_swap.cpp`.

- [ ] **Step 1 — failing test:** lava-under-water inverts (lava sinks); equal-mass same-pair holds; a STONE
  (`viscosity=∞`) cell never swaps; the gate reads **density**, not `P` (verify by adding overburden above —
  the swap decision is unchanged, Archimedes).
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** the gate: vertical pair swaps iff energy-lowering (`dPE < −1e-3`) **AND**
  `(m_up − m_low)·g > swap_resistance(Mi,Mj)`. **PORT `swap_resistance`** (`swap_kv·√(visc_i+visc_j) +
  swap_kc·min(min_mass)·g`) verbatim — it's locked (label: viscosity term is a **bounded proxy**, L6 debt).
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): density-difference swap gate (lava sinks; reads ρ not P)`

### Task 3.2: swap is a pure permutation

**Files:** `tests/engine_b2_swap.cpp`.

- [ ] **Step 1 — failing test:** after a batch of swaps, grand + per-species mass **bit-exact** to the start;
  STONE unchanged; greedy conflict-free (each cell in ≤1 swap/tick).
- [ ] **Step 2 — run, expect FAIL/PASS.**
- [ ] **Step 3 — implement** the swap as a wholesale copy of `(mass, matIx, E, v)` from the snapshot (permutation
  ⇒ fabrication structurally impossible); greedy by force-difference priority.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `test(engine-b2): swap is a conservative permutation (bit-exact)`

---

## Stage 4 — Heat (enthalpy + conduction + advected energy)

**Goal:** L5. Cell carries enthalpy `E`; conduction is the same 6-face flux; `T` derived; no separate pass; no
temp-ghost.

### Task 4.1: enthalpy as the carried scalar, T derived

**Files:** `core/engine_b2.hpp` (decode); Test `tests/engine_b2_conduct.cpp`.

- [ ] **Step 1 — failing test:** set a cell's `E`; after DECODE `T == E/(mass·cp)` (±1e-4); set `T`+mass,
  round-trip `E` recovered. Thin cell (`mass→1e-6`) carries `E→0` ⇒ cannot hold a stale hot `T` (anti-ghost).
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** `E` as the carried field (init `E = mass·cp·T_seed`); DECODE derives `T =
  clamp(E/(mass·cp), stencil_min, stencil_max)`.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): enthalpy E is the carried scalar; T derived (anti-ghost)`

### Task 4.2: conduction = 6-face −k∇T, energy-exact

**Files:** `core/engine_b2.hpp` (`resolve_conduct`); Test as above.

- [ ] **Step 1 — failing test:** pure conduction (no flow) relaxes a hot/cold pair toward uniform `T` (Fourier);
  grand energy **exact** every step; `T` stays within the neighbour stencil every step (max-principle, the
  audit-#3 case: one 290 K bucket beside 6000 K does NOT explode the section); symmetric multiplier ⇒
  antisymmetric flux.
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** `ΔE_i = dt·Σ_faces k_face·(T_j − T_i)·A/dx`, `k_face = k_base·(1+C·a_i)(1+C·a_j)`,
  `a = 1/(1+μ/μ_ref+yield/yield_ref)` (thermal mobility from the mechanical numbers, no phase enum). **PORT the
  max-principle clamp verbatim** (forward-Euler is unstable on thinned cells without it). Conduction moves
  **energy**; `T` re-derived after (Task 4.1).
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): Fourier conduction (6-face −k∇T, energy-exact, max-principle)`

### Task 4.3: advected enthalpy (heat rides moving mass)

**Files:** `tests/engine_b2_conduct.cpp`.

- [ ] **Step 1 — failing test:** thermal convection — hot water that flows (Stage 2) carries its `E` with it;
  grand energy exact; a hot parcel moving into a cold region raises the destination `T`.
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** carry `ΔE = ṁ·h_donor` on every mass flux in Task 2.2's advection (the same
  antisymmetric flux moves `mass` and its `E` together). No new pass.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): advected enthalpy (convection rides the mass flux)`

---

## Stage 5 — Phase change (simple threshold relabel)

**Goal:** `min_temperature→min_target` / `max_temperature→max_target` per material; relabel in DECODE; **keep
`E`, re-derive `T`** (conservation-clean, L4/L5). Latent heat explicitly out of scope (user, 2026-06-09).

### Task 5.1: per-material phase fields

**Files:** Modify `core/sim_engine.hpp` (LUT) + material datapack JSON; Test `tests/engine_b2_phase.cpp`.

- [ ] **Step 1 — failing test:** LUT loads water `{min 273→ice, max 373→steam}`, lava `{min ~1000→stone}`,
  ice `{max 273→water}`, stone `{max ~1000→lava}` (exact ids/temps per the real material JSON).
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** four fields (`minTemp,minTarget,maxTemp,maxTarget`; target = matIx or sentinel
  `NONE`) in `Material`/LUT + strict JSON loader (canonical, material-id targets).
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): per-material phase threshold fields (datapack + LUT)`

### Task 5.2: DECODE relabel, keep E

**Files:** `core/engine_b2.hpp` (decode); Test as above.

- [ ] **Step 1 — failing test:** a water cell cooled below 273 relabels to ice — **`E` unchanged**, `T`
  re-derives with `cp_ice` (so `T` may step, energy does NOT); mass exact. Hysteresis: a cell at exactly 273
  does not flicker phase across consecutive ticks (freeze at `T_min`, re-melt at `T_min+ε`).
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** in DECODE: if `T < minTemp` and `minTarget≠NONE` → set `matIx=minTarget`, **keep
  `E`**, re-derive `T`; symmetric for `maxTemp`. Add the small hysteresis band.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): phase change = DECODE relabel (keeps E, T re-derives)`

---

## Stage 6 — Resistance hardening + residue cleanup

**Goal:** L6 fully + the strict cohesion gate (replaces the loose `subMin<125`).

### Task 6.1: yield threshold (dormant) + dormancy golden

**Files:** `core/engine_b2.hpp`; Test `tests/engine_b2_resistance.cpp`.

- [ ] **Step 1 — failing test:** with all terrain `yield_stress=∞`, the engine is **bit-identical** to a build
  with the yield gate removed (dormancy golden) — proves the gate is wired but inert. (Finite-τ granular yield
  is DEFERRED — spec §6.)
- [ ] **Step 2 — run, expect FAIL/PASS.**
- [ ] **Step 3 — implement** the post-RESOLVE net-force yield gate (`moves iff net F > Σ yield`), `∞` for all
  current terrain ⇒ no-op; assert the golden.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): dormant yield gate (∞ terrain, bit-identical golden)`

### Task 6.2: strict cohesion (no settled 0<m<min)

**Files:** `tests/engine_b2_resistance.cpp`.

- [ ] **Step 1 — failing test (strict):** after a 2D spread SETTLES, **zero** cells at `0 < m < min`; repeated
  pours (4000 steps) leave **zero** persistent sub-min and do **not** accumulate. (This is the law-strict
  replacement for the old `subMin<125`.)
- [ ] **Step 2 — run, expect FAIL** (transient sub-min frontier cells exist until cleanup lands).
- [ ] **Step 3 — implement** DECODE sub-min handling: a flow never *leaves* a cell at `0<m<min` (drain-fully or
  coalesce into the lowest neighbour). Conserve per-species.
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `feat(engine-b2): strict cohesion — no settled sub-min residue`

### Task 6.3: viscosity-as-rate (the L6 debt close)

**Files:** `core/engine_b2.hpp`, `tests/engine_b2_resistance.cpp`.

- [ ] **Step 1 — failing test:** a buoyant but viscous pair **eventually** swaps (viscosity slows the cadence,
  never blocks) — a high-viscosity/low-buoyancy pair that should slowly sink does, given enough ticks.
- [ ] **Step 2 — run, expect FAIL.**
- [ ] **Step 3 — implement** viscosity as a swap **rate/cadence** (probability or accumulator per pair), moving
  it OUT of the threshold; keep `swap_kc` cohesion as the threshold. (If no real-LUT pair exercises this,
  document it as a material-gated limitation and keep the bounded proxy — note which.)
- [ ] **Step 4 — run, expect PASS** (or documented-deferred with the proxy retained + a comment-test).
- [ ] **Step 5 — commit:** `feat(engine-b2): viscosity = swap rate, not threshold (L6 debt closed/deferred)`

---

## Stage 7 — Integration, JNI, perf, in-game gate

**Goal:** the clean core is live, conserves over a soak, meets the perf ceiling, and is handed to the in-game
audit.

### Task 7.1: wire the JNI path to `step_world_b2`

**Files:** the JNI bridge; Java `:core:integrationTest`.

- [ ] **Step 1 — failing test:** the Java integration suite (real `.so`) runs `orgeStepWorld` → `step_world_b2`
  and passes (mass conserved, fluids move). Build the `.so`.
- [ ] **Step 2 — run, expect FAIL** (still pointed at old engine).
- [ ] **Step 3 — repoint** the JNI `orgeStepWorld` at `step_world_b2`; keep the ABI (6-array marshalling)
  unchanged (reuse).
- [ ] **Step 4 — run, expect PASS** (`:core:test` + `:core:integrationTest` green on real `.so`, both loaders
  build).
- [ ] **Step 5 — commit:** `feat(engine-b2): live JNI path on the clean core`

### Task 7.2: conservation soak + L7 order-invariance

- [ ] **Step 1 — failing test:** 5000-step mixed scene (water + lava + air + stone, pours + heat source) —
  grand mass, per-species mass, grand energy exact throughout; forward/reverse iteration bit-identical.
- [ ] **Step 2–4:** run; fix any leak at its source (never a reconciler patch); PASS.
- [ ] **Step 5 — commit:** `test(engine-b2): conservation soak + order-invariance (L4/L7)`

### Task 7.3: perf gate

- [ ] **Step 1:** measure ms/cell for `step_world_b2` (the relaxation sweeps `K` are the cost). Record vs a
  ceiling in the plan (e.g. ≤ N µs/cell; the relaxation is the ~100× watch-item).
- [ ] **Step 2–3:** if over budget, lower `K`/raise `relax` within the L2-passing range; if a real fix needs a
  faster solver (multigrid / two-step projection) → **ESCALATE** (architecture change, not a tweak).
- [ ] **Step 4 — commit:** `perf(engine-b2): relaxation cost measured + tuned to ceiling`

### Task 7.4: build, bump, in-game audit handoff

- [ ] **Step 1:** rebuild `liborge.so`, bump the parent gitlink, push (per `[[always-push-rebuild]]`).
- [ ] **Step 2:** write the in-game audit checklist: leveling, pool→air spread, **deep-hole gush**, **U-tube
  equalize**, lava sinks, conduction (water boils by lava / lava won't freeze), phase relabel, **no settled
  residue/ghosts**, no fabrication/overshoot, watch lag.
- [ ] **Step 3 — commit + handoff:** `chore(engine-b2): live build + in-game audit checklist`. The in-game
  audit is the final gate (headless invariants are necessary, not sufficient).

---

## Anti-drift gates (apply to EVERY task)

- **Run before enshrining:** an invariant is "done" only after it RAN green against the engine (the INV-1b
  lesson). Cite the run in the commit.
- **Spec wins = LAW wins:** if a task tempts you toward a second pressure field, a direction-split force, a
  stored-T-as-truth, or a separate conduction pass — **stop, it's drift** (DESIGN-LAW drift test). The fix for
  "leveling won't work" is always "make `P` correct (Stage 1)", never "add a horizontal head term."
- **Real LUT only** (no synthetic Materials). **Conserve at the source** (never a reconciler patch).
- **Independent mechanism oracle** (Stage 7): a separate agent re-derives one observable from the LAW only, on a
  NEW geometry, with an analytic `ρg·d` reference, AND runs the L1 manifest check — observable-only is
  insufficient.

---

## Self-Review (coverage vs the law)

- **L1 one P** → Task 1.2 (manifest) + the reuse table (no `own_weight_head`). ✅
- **L2 hydrostatic P** → Stage 1 spike + lock (1.1/1.2), U-tube 1.3/2.3. ✅
- **L3 isotropic force** → 2.1, depth-efflux 2.5. ✅
- **L4 conservation/bounds** → 0.2 helpers + 2.2, 3.2, 7.2; strict cohesion 6.2. ✅
- **L5 heat** → Stage 4 (4.1 enthalpy, 4.2 conduction, 4.3 convection). ✅
- **L6 resistance** → 3.1 (threshold=ρ/resistance), 6.1 (yield dormant), 6.3 (viscosity=rate). ✅
- **L7 one snapshot/order-invariant** → 2.2 + 7.2. ✅
- **Phase (user-chosen simple model)** → Stage 5. ✅
- **Pipeline ENCODE→RESOLVE→DECODE** → Stage 0 shell, used throughout. ✅
- **Reuse (no needless re-derivation)** → reuse table; ports cited in 2.2/3.1/4.2/7.1. ✅

*No placeholder steps; types/names (`Chunk::P`, `relax_pressure`, `swap_resistance`, `step_world_b2`,
`minTemp/minTarget/maxTemp/maxTarget`, `assert_conserved/bounds/energy`) are consistent across tasks. The one
genuine risk is concentrated and gated: Stage 1's relaxation must hit L2 — if it can't, that's an escalation,
not a paper-over.*
