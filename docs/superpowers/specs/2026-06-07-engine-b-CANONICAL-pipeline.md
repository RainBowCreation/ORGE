> # ⚠ NOTE 2026-06-10: the one-sentence law (ENCRYPT→RESOLVE→DECRYPT, un-mixed maps, one resolver) STANDS;
> the mechanism details below (EOS-as-pressure-modifier, reflection-emergent overburden, swap barrier
> composition) are superseded by [`2026-06-10-engine-b-unified-spec-v4.md`](2026-06-10-engine-b-unified-spec-v4.md).

# Engine-B — CANONICAL pipeline (READ THIS FIRST, before any code)

**Date:** 2026-06-07 · **Status:** CANONICAL (user-reaffirmed 2026-06-07, in-game audit).
**Track:** `rebuild` (parent `/home/claude/ORGE-B` ↔ engine `/home/claude/ORGE-B/ORGE-ENGINE`).

> **DO NOT reason about the engine from the C++ code.** The code on this branch has drifted from the
> design more than once. **Follow the spec.** This file is the law; the two detail specs below are the
> math/model. If code and spec disagree, the **spec wins** and the code is the bug.

---

## THE LAW (one sentence)

The engine step is **ENCRYPT → RESOLVE → DECRYPT, performed in ONE `step_world` call** — now carrying
**multiple un-mixed per-cell vector maps** (force/momentum, pressure, heat) instead of the single bundled
energy vector of the 2026-06-04 spec. Everything else (the resolver, the EOS pressure modifier, the
force-threshold swap, antisymmetric conservation) is unchanged from 2026-06-04.

## The three phases (all inside one `step_world`)

1. **ENCRYPT** — per cell, **own state only** (zero neighbour reads, GPU-ideal). Build the cell's
   **vector maps**: the mechanical drive (gravity + external + Bingham yield on net force), the **pressure
   modifier** `p = f(current_mass; min, default, max)` (EOS §B.1), and the thermal amplitude. *Multiple
   maps, un-mixed* — this is the one change from the old single energy vector `E=(Ex,Ey,Ez)`.

2. **RESOLVE** — **the ONE cross-cell step (the "resolver").** Net the vector maps over the 6 faces with
   **antisymmetric** flux from a single pre-step snapshot. This is where **pressure-at-depth, hydrostatic
   head, leveling, buoyancy, cross-species reorder, and conduction all EMERGE.** Pressure propagates
   *through* full (incompressible) cells here and accumulates downward → a taller column pushes the shorter
   one level. **A purely-local per-cell EOS cannot do this — only the resolver can.** Conservation lives
   here and only here (antisymmetric ⇒ grand mass + per-species mass + grand energy exact).

3. **DECRYPT** — per cell, **own state only**. Re-partition the resolved `(Δm, Δp⃗, ΔE)` back into
   `mass`, `velocity`, `temperature`. Vacuum/CFL guards. 0-mass cells relabel to **VACUUM** (matIx 0).

## Material / pressure facts (ratified, do not re-derive)

- **`max_mass` is the hard per-cell wall** via the EOS `^γ` stiff ramp. For **all solids and liquids
  `max_mass == default_mass`** (incompressible — they do not over-compress; the wall is at full). Gases
  have `max_mass > default_mass` (compressible band).
- **The "pressure modifier" IS the EOS** `p(m,T)` of §B.1 in the unified-formula spec — a function of
  `current` vs `min` vs `default` vs `max` mass. `p = 0` at rest density (free surface). Pressure rises
  with depth **dynamically, in RESOLVE** (the resolver accumulates it), not from a static local term.
- **Cross-species reorder = force-difference swap** (energy-lowering): on a vertical pair, swap when the
  upper's **local buoyant force** `(ρ_up − ρ_low)·g·V` exceeds the **pair's resistance** (viscosity /
  cohesion) — heavy-on-light. **It is NOT gated on `chi`/compressibility** (`chi(lower) ≥ 0.5` is stale
  drift — delete it), and the barrier is the **pair's material resistance, NOT a global `swap_threshold`
  constant** (refined 2026-06-07 PM). Overburden cancels in the swap (Archimedes — depth-independent). The
  swap is the **discrete branch of the one RESOLVE force**: it *flows* where mass can move, *swaps* where
  two full immiscible cells block the flux.
- **Pressure-at-depth EMERGES over ticks** in the ONE snapshot vector resolve, from **gravity +
  incompressible reflection** — NOT a global "Σ mass above" sum, NOT an EOS compression band (refined
  2026-06-07 PM). The live code's local-EOS `p_face` is `0` at rest under `max==default` ⇒ the leveling
  bug. One force VECTOR, one pass, every read a single neighbour, yield-gated transmission (fluid passes +
  own weight down / unchanged sideways; locked solid bears+blocks; vacuum resets). ~~See the decomposition
  design §8 for the full refined model~~ **(2026-06-10: decomp §8 superseded — the refined model is now
  `2026-06-10-engine-b-unified-spec-v4.md` §3–§7.)**
- **No phase/state branch.** One branchless law per cell; `yield_stress` is the universal axis
  (fluid = 0, solid = huge, bedrock = ∞). Immovable = `viscosity = ∞` (data, not a branch).

## Detail specs (the math + the model — RESTORED 2026-06-07, were wrongly deleted in `0687247`)

- **`2026-06-04-engine-b-unified-formula.md`** — the MATH. §B ENCRYPT, §C RESOLVE, §D DECRYPT, §B.1 the
  EOS/pressure modifier, §J.5 the hydrostatic-balance proof, §E conservation proofs. *Read §B–§D + §J.5.*
  (Its single `E=(Ex,Ey,Ez)` is now multiple un-mixed maps — the only delta vs this canonical file.)
- **`2026-06-04-engine-b-velocity-field-design.md`** — the MODEL (design laws L1–L9, why one carried
  vector, why no species branch).

## What is STALE / DRIFT (do NOT reason from these)

- **`core/force_advect.hpp`** (`forcePass`+`advectPass`+`stepForceAdvect`, the "Force→Advect" Stage-1
  core) — it implements 3 **separate local passes with a purely-local EOS and NO resolver**. That is the
  drift that **broke in-game leveling** (local EOS under a hard `max==default` cap = zero pressure-at-depth
  = no flow). It is currently wired into the live JNI path; that wiring must be reverted to the
  encrypt→resolve→decrypt step.
- **`2026-06-06-engine-b-force-advect-conduct-design.md`** — its **un-mixing into multiple channels is
  CORRECT and kept**, but its "removed the energy vector + Decrypt", "3 local passes", and "buoyancy/head
  emerge from a *local* EOS ∇p" framing is **WRONG**. Un-mixing must happen **inside ENCRYPT→RESOLVE→DECRYPT
  (keep the resolver)**, not as 3 local passes. See the banner at the top of that file.
- **`core/engine_b.hpp`** `encrypt_world`/`resolve_world`/`decrypt_world` is the **closest existing match**
  to the law (it IS encrypt→resolve→decrypt) but still has drift to fix (e.g. the `chi` swap gate, single
  energy vector vs multiple maps). Treat it as a starting point to refactor toward this spec, not as truth.

## In-game audit findings carried forward (2026-06-07) — the bugs to fix against THIS spec

1. **No leveling / no horizontal flow** (root: local-EOS drift, no resolver → no pressure-at-depth).
2. **Lava won't sink under water** (root: stale `chi ≥ 0.5` swap gate; use force-difference threshold).
3. **Sub-min-mass residue + 0.0-mass ghost cells** (DECRYPT must relabel drained cells to VACUUM +
   restore min_mass cohesion; this also stops the Java `inject=orge:air` queue flood).
4. Confirmed-good: no mass fabrication, no max_mass overshoot.
