# Handoff: soft-wall restoring force + universal yield (2026-09-30)

Two open tasks. Both come from the DESIGN-LAW v4.4 amendment (#8 universal yield, #9 INV-SOFTWIN) and
from `specs/2026-09-30-phase-change-mass-window-proposal.md` (rev 2). **Read DESIGN-LAW.md first,
verbatim.**

## What already shipped (rebuild @ 66cce80 / 9472502, engine da0540e)
- **Phase relabel always happens, keeping mass and E** (`engine_b.hpp` terrain branch ~3364, fluid
  branch ~3529). Only the target-validity and VOID (`heatCapacity > 0`) guards remain.
  `freeze_evict_world` still evicts a lower-max surplus first; when it defers, the cell relabels over-max.
- **Java never decides species.** `PhasePlanner` and `PhaseChangeDecider` are deleted, and
  `FluidReconcileDecider` paints the engine's out-species. The self-write guard
  (`LiveMaterials.selfWrite`) stops ORGE's own `setBlock` from being captured as a player placement.
  The per-species ledger treats a phase relabel as ledger-neutral (`StepValidator`).
- Result: lava 2650 → stone at ~1275 K (the 290 K reset is gone); puddles freeze; over-max relabels rest.
- Also shipped: `/orge step [ticks|dt]` and `/orge perf` (Scheduler runtime cadence/dt knobs + StepMetrics).

## Task A: restoring wall (INV-SOFTWIN "driven back")

**Goal.** An out-of-window cell (over-max lava from melting stone at 2700 > 2650; over-max ice at 1000 >
917 when freeze_evict deferred; sub-min cells) is driven back toward `[min, max]` by the EOS wall:
- push `K·max(0, m − max)`
- pull `K·(1−χ)·max(0, min − m)`
- exactly 0 inside the window, so it is bit-parity for all in-window scenes

**Why it was deferred (measured by the step-1 engine agent).** Adding the wall to the relaxation
anchor/stiffness turned into a heat pump: 954 J/tick at K = 500 Pa/kg, 2.2e4 J/tick at K = 2000,
1.9e5 J/tick at K = 1e4, with no mass moving. The wall-less over-max cell rests at 0.3 J/tick. The push
has no vent, because three gates block its escape:
1. the **empty-refill min-quantum gate**: a partial flux into a vacuum cell is blocked below min
   (~2532, 2558–2563, vacClaim ~2125–2160);
2. the **liquid drive lag**: liquids move on the previous tick's velocity, so the wall force
   accumulates and rails at the CFL cap, and vel_damp turns it into heat;
3. the **own-P vacuum ghost**: a face against vacuum uses the cell's own P, so there is no gradient
   to vent into.

A plain relax source also drifts without bound in a sealed region: +1.2 MPa/tick in a 2-cell box.

**Pointers.**
- D1/D3 gate inventory: the proposal rev 2 §2.1, and the review notes in this session.
- Suggested order: rework the vent path first (min-quantum refill → continuous floor; a vacuum face
  gradient), then re-add the hinge wall. Gate on energy drift, not only on mass.
- DEFERRED note in code at `engine_b.hpp` ~3533.

**Acceptance.**
- `engine_b_softwall_curve_test`: in-window scenes unchanged with the wall on vs off.
- An over-max lava cell next to lava/vacuum with room sheds to ≤ max within N ticks, and ΣE + boundaryE
  drift is below the INV-AL gate (150 J/tick).
- A sealed over-max cell rests (no drift).
- The native cheap tier has no new red vs `ORGE-ENGINE/build/BASELINE_REDS_81c28b2.txt`, the list of
  failures that pre-date this work: 10 red tests, including a compile failure in
  `engine_b_seed_rest_density_test`, which uses the removed `is_gas`. `stress_test` quick
  "clamp holds [0,6000]" is also pre-existing red.

## Task B: universal yield (step 2; do after Task A)

**Goal (law #8, v4.4).** `yield_stress` gates EVERY mass motion (flow, expansion into a new cell, swap)
as the saturating weight `max(0, F − τ_y)`. Today it is used only in `swap_resistance`
(`engine_b.hpp:245`). Natural solids then get a finite μ plus a large τ_y; only bedrock/barrier keep ∞.

**Design (D2/D3):**
- a continuous Bingham weight `yf = max(0, σ − τ_y)/(σ + ε)`, with no cached rigid flag (law #0);
- `wall_weight` (~275) `= present·wallcap·(1 − mobile·yf)`;
- relax `frozen` (~651) `= present·(1 − mobile·yf)`;
- DECODE momentum scaled by `mobile·yf` (otherwise gravity → vel_damp heat on overhangs, D3 M1);
- Nusselt `Ra·yf`;
- delete the swap pre-gate `mob<=0 → continue` (~1611), because `swap_resistance` already has τ_y;
- σ must be local. Two options:
  - **the own wall term only** (D3 M3). This keeps ENCODE local (law #4), and deep stone never yields
    under overburden. But without Task A's wall, σ ≈ 0 everywhere, so nothing ever yields. That is why
    B depends on A.
  - **the law #8 text literally**: net face force vs `max(τ_y)`.

  Pick one and record it.
- Every `mob<=0` / terrain site is in D2's inventory: ~1168, 1246, 1609, 1984, 2154, 2357, 2720 (the
  radiation mirror — keep tinted_glass μ = ∞), 2914, 2995, 3053, 3335.

**Data** (`core/src/main/resources/data/orge/orge/materials/*.json`):

| Material | μ (Pa·s) | τ_y |
|---|---|---|
| stone | 1e9 | 1e8 |
| ice | 1e7 | ~1e5 (game-scale, so bursts are visible) |
| generic_solid | 1e9 | 1e7 |

Pinned blocks, tinted_glass and bedrock stay ∞. These μ are game-scale post-yield rates.

**Java:**
- `Material.movable()` must become `isFinite(μ) && yieldStress < defaultMass·g`. Otherwise the
  painter treats stone as a fluid and `StepValidator` tracks it.
- A solid that drains fully to vacuum keeps its block on screen (the T1 note). It needs a
  REMOVE-to-air path for non-movable out-species with mass ≈ 0.

**Acceptance:**
- resting stone/ice with finite μ is bit-identical to the μ = ∞ baseline over 1000 ticks;
- over-full ice (after Task A) extrudes until P ≤ τ_y, with Σm and ΣE exact;
- Nu == 1 for stone at ΔT = 1000;
- Java `MaterialTest.movable` and `FluidReconcileDeciderTest` (stone is never fluid-painted).

## Future work (not in scope): proposal §10
Carrier+passenger mixtures (humidity/fog/mud), air/steam `min_mass` capping hot-air expansion (probe:
the hot cell stops at 1.0 kg instead of 0.58), no p·dV term, droplets/wind, and biome temperature
applying only at worldgen (memory `orge-biome-temp-worldgen-only`).

## Test loop
- Native: `cd ORGE-ENGINE && ./tests/run_tests.sh`. Grep the log for FAIL/COMPILE; never trust a piped
  exit code.
- Java: `JAVA_HOME=$HOME/.jdks/dragonwell-21.0.11 rtdd run`; before merge, `./gradlew :core:check
  -Dorg.gradle.java.home=$HOME/.jdks/dragonwell-21.0.11`. The SchedulerTest timing tests flake under
  CPU load, so re-run on an idle machine.
- The engine library is `ORGE-ENGINE/native/build_liborge.sh` → copy to
  `core/src/main/resources/natives/linux-x64/liborge.so`. That path is gitignored, so commit it with
  `git add -f`.
