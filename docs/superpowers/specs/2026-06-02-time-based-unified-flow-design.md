# Time-Based Unified Flow (`rate·dt`) + Combined Every-5-Tick Step

**Date:** 2026-06-02
**Status:** Design — approved, pending spec review
**Supersedes:** the *step-based* advection rate model of `2026-06-01-unified-fluid-engine-design.md`
(the ONE-substance / three-pass structure is unchanged; only the rate term and the
scheduler cadence change).

## Problem

The engine passes one `dt` parameter that **conduction honours and advection ignores**.
Conduction is a time-integrator (`∂T/∂t = ∇·(k∇T)`, forward-Euler, `dt` physical).
Advection is a relaxation solver whose transfer is a **fixed fraction per call** —
`spread_fraction(viscosity)`, dt-blind. Any time `dt` varies (e.g. a catch-up step),
the two passes silently advance by different amounts of simulated time. That is a latent
correctness bug and a conceptual inconsistency.

Separately, the scheduler runs conduction at 1 Hz (`dt=1.0`, every 20 ticks) and advection
at 4 Hz (`dt=0.25`, every 5 ticks). Heat updates feel sluggish, and one `dt=1.0` explicit-
Euler step is large enough to risk oscillation for high-conductivity / low-capacity cells.

## Goal

**Timing correctness:** make flow genuinely time-based (`rate·dt`) so a varying `dt`
advances both passes by the same simulated time, and run both passes on the same 5-tick
cadence with a correct catch-up policy. **Continuous flow behaviour at `dt=0.25` must be
bit-identical to today** for fluids that did NOT use the frontier cadence (i.e. fast,
low-viscosity fluids like water) — that is the calibration constraint. Two behaviours
intentionally change: (a) viscous-fluid frontier timing, because the integer-step cadence
is removed and slowness becomes purely rate-driven (A3); (b) heat cadence (B2).

## Non-Goals (YAGNI)

- No reformulation of the gravity pass. The vertical molar-mass **swap stays a discrete
  swap** (it is the gravity *sort*, conservation-exact by permutation, and CFL-correct at
  1 cell/step). Only the horizontal/displacement flows change.
- No implicit solver. We stay explicit and respect CFL by sub-cycling.
- No JNI ABI change.

## Key physics finding (why catch-up still costs N×)

There are **two** CFL limits, binding at different `dt`:

| limit | caps | binding `dt` (water) |
|---|---|---|
| flux overshoot | mass across one face ≤ `0.5·Δ` (the to-equilibrium amount) | ~1.0 s |
| **propagation** | front/fall ≤ **1 cell per step** | **0.25 s** |

Mass landing in cell+1 this step was not there at snapshot time, so it cannot move to
cell+2 this step. Therefore a physically-correct advance of `dt > 0.25 s` **must sub-cycle**
at the 0.25 s propagation quantum. A single large-`dt` step is only a mass-conserving
approximation (propagation lags, distribution coarsens). **You cannot beat CFL on compute:**
a 0.5 s advance costs ~2× a 0.25 s advance, expressed as one call or two.

What `rate·dt` *does* buy: (1) consistency — one `dt` drives both passes; (2) engine-owned
sub-step sizing; (3) correct fractional / variable-`dt` scaling (finer cadences, time-
acceleration); (4) bit-identical behaviour at the default `dt=0.25`.

---

## Part A — Engine: `rate·dt` unified flux

### A1. The one flux formula

Every horizontal/displacement transfer across a face — **same-material (Pass B) AND
into-a-lower-molar-mass neighbour (Pass B′)** — uses the identical calculation:

```
pressure(cell) = max(0, mass − min_mass)
Δ              = donor.pressure − receiver.pressure          // donor = higher-pressure side
fraction       = clamp( rate(donor.viscosity) · dt , 0 , 0.5 )
dm             = fraction · Δ
```

- The donor's viscosity governs the rate in both cases (water displaces air fast; lava
  slowly — emergent, no special-casing).
- `0.5` is the no-overshoot ceiling (cannot transfer more than reaches equilibrium).
- Pass B and Pass B′ collapse into **one routine**; they differ ONLY in the face-validity
  predicate (same-species OR receiver-has-strictly-lower-molar-mass). The transfer math is
  shared. Existing B′ specifics retained: the vacuum-adopt single-donor CLAIM, the
  frontier-distance concentration field (budding to `floor(M/min)`), and the
  antisymmetric `BAccum` ledger (donor `−dm`, receiver `+dm`, enthalpy `dm·T`).

### A2. The rate function

```
rate(v) = 2 · min(ADV_SPREAD_K / v, ADV_CFL_CAP)      // ADV_SPREAD_K=1.0, ADV_CFL_CAP=0.25
```

Calibrated so `rate(v)·0.25 == spread_fraction(v)·0.5` (today's per-step transfer):
- water (`v≈0.001`): `spread_fraction` capped at 0.25 → `rate=0.5` → at `dt=0.25`,
  `fraction=0.125` (== today). At `dt=0.5`, `fraction=0.25`. At `dt≥1.0`, clamps to `0.5`.
- lava (`v=100`): `spread_fraction=0.01` → `rate=0.02` → at `dt=0.25`, `fraction=0.005`
  (== today).

`rate()` and the flux formula live in `orge_kernel.hpp` (shared header) so `sim_engine.hpp`
and any kernel path stay **bit-identical**.

### A3. Viscosity folded into rate; cadence deleted

`advances_this_step`, `advance_period`, and the internal `static std::atomic<long> advStep`
counter are **removed**. Lava is slow purely because high viscosity → low `rate` → small
flux → slow accumulation to the `2·min_mass` budding threshold. One slowness mechanism,
fully dt-consistent. `spread_fraction()` is replaced by `rate()`; `SWAP_HYST` is unchanged.

**This intentionally changes viscous-fluid frontier timing** (not bit-identical). Note the
rate-driven slowness *approximately reproduces* the old cadence: lava accumulates ~25× slower
than water (`rate` ratio 0.005 : 0.125), so it buds ~25× less often — close to the old
`advance_period(lava)≈25`. Exact tile-opening timing differs; validated in the in-game audit,
not pinned bit-for-bit.

### A4. Gravity (Pass A) — unchanged

The vertical molar-mass swap is kept verbatim (full-cell swap, top-down, one-swap-per-cell
claim, `SWAP_HYST` 5%). It runs once per sub-step → a column falls `n_sub` cells over `dt`.

### A5. Sub-cycling wraps the whole step

`orgeStepWorld(..., dt, passes)` internally:

```
n_sub  = max(1, round(dt / DT_CFL))        // DT_CFL = 0.25 s (propagation quantum)
sub_dt = dt / n_sub
repeat n_sub times:
    if (passes & PASS_CONDUCTION) conduction_step(sub_dt)     // heat → flow order (Decision 2)
    if (passes & PASS_ADVECTION)  advect_substep(sub_dt)      // Pass A swap, then unified B/B′
```

At `dt=0.25` → `n_sub=1` → exactly today's combined call. The `long stepIndex` argument to
`advect_world` is replaced by `double dt`; the JNI threads the existing `jdouble dt` into it.
**JNI ABI is unchanged.**

---

## Part B — Scheduler: combined step every 5 ticks

### B1. Cadence
Both passes fire every **5 ticks** (`ADVECTION_TICKS=5` retained). The 20-tick conduction
boundary and `TICKS_PER_STEP`-gated conduction branch are **removed**. Each boundary:
one `snapshotColumns` → one combined JNI call (`PASS_CONDUCTION|PASS_ADVECTION`) → one
atomic writeback. No new server-thread cost (the snapshot already happened every 5 ticks).

### B2. Heat cadence change (intentional, NOT bit-identical)
Conduction moves from 1 Hz / `dt=1.0` to **4 Hz / `dt=0.25`** — smoother and more
numerically stable (4 small Euler steps/sec vs 1 large one), at negligible extra background
cost (conduction is the cheap stencil pass). *Flow stays bit-identical at `dt=0.25`; heat
intentionally changes.*

### B3. Async, single-in-flight, no hard-cancel
Compute stays on the background thread; the game never blocks. The existing single-in-flight
state machine is retained. **No hard-cancel at 5 ticks** — if a job overruns, the in-flight
gate naturally skips the next boundary (self-throttle). The existing late/grace + health
throttle is retained.

### B4. Overrun policy — clamped accumulator (Decision 1)
On each submit:
```
dt = clamp( ticks_since_last_writeback / 20.0 , 0.25 , MAX_CATCHUP )    // MAX_CATCHUP = 0.5 s
```
- On-pace (5 ticks elapsed) → `dt=0.25`.
- One missed beat (10 ticks) → `dt=0.5` → engine sub-cycles to 2× internally (correct).
- Beyond the clamp → debt dropped → graceful slow-motion (no spiral, because per-job work
  is bounded by `MAX_CATCHUP`). `MAX_CATCHUP` is tunable in the in-game audit.

### B5. Writeback integrity (retained)
§9 validation (T after conduction, `+Σmass` after advection) and the
`MaterialChangeReseed` edit-reconciliation (signature-gated, handles player edits during a
longer-than-5-tick in-flight window) are unchanged.

---

## Testing (test-first, against the conservation oracle)

1. **Headline / behaviour-preservation:** for a fast (non-cadence) fluid like water, flow at
   `dt=0.25` reproduces current engine output **bit-for-bit** (the refactor preserves the
   continuous transfer behaviour). Viscous fluids are excluded here — see test 8b.
2. **Sub-cycling correctness:** `advect(dt=0.5)` ≡ two `advect(dt=0.25)` steps (same final
   state).
3. **Unified flux equivalence:** same-material and into-lighter transfers produce the same
   `dm` for equal `(viscosity, dt, Δpressure)`.
4. **Conservation:** mass oracle `== 1000.0` across all flow scenarios; antisymmetric ledger
   holds across chunk seams.
5. **Kernel / `sim_engine` parity:** the shared `rate()` / flux helpers keep both paths
   bit-identical (existing parity suite green).
6. **Rate calibration:** `rate(v)·0.25 == spread_fraction_old(v)·0.5` for water and lava.
7. **Scheduler:** combined-call writeback fires both passes from one snapshot; overrun holds
   then clamps (no cancel); `dt` clamps to `[0.25, MAX_CATCHUP]`; reseed reconciliation
   intact after a late writeback.
8. **Heat cadence (characterization, not bit-identical):** conduction at 4× `dt=0.25`
   produces a stable, monotone-toward-equilibrium temperature field (no oscillation that the
   old `dt=1.0` step risked).
8b. **Viscous frontier (characterization, not bit-identical):** lava spreads slowly via its
   low `rate` (no cadence), reaching `floor(M/min)` tiles at roughly the old pace; pinned as
   a range/monotonicity check, not bit-for-bit.

## Risks / mitigations

- **Calibration drift** — wrong `rate()` changes flow feel. Mitigation: test 6 pins the
  identity; test 1 pins bit-identical flow at `dt=0.25`.
- **Heat behaviour change** — intentional; covered by test 8, validated in the in-game audit.
- **Catch-up spiral** — bounded by `MAX_CATCHUP` clamp (B4).
- **Parity break** — shared-header helpers + test 5.

## File touch list (anticipated)

- `ORGE-ENGINE/orge_kernel.hpp` — add `rate()`, `DT_CFL`; remove `spread_fraction`/cadence.
- `ORGE-ENGINE/sim_engine.hpp` — unify Pass B/B′ flux on `rate·dt`; sub-cycle loop in the
  world step; delete `advances_this_step`/`advance_period`/`advStep`.
- `ORGE-ENGINE/orge_jni.cpp` — thread `dt` into advection; drop the static counter.
- `core/.../scheduler/Scheduler.java` — combined every-5-tick submit; clamped-accumulator
  `dt`; remove the 20-tick conduction boundary; no hard-cancel.
- `.so` rebuild + gitlink bump.
- Tests across `:core` and the engine `tests/`.
