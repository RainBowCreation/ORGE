> # ⚠ HISTORICAL 2026-06-10: the T1–T8 track this plan drove has shipped; its model is superseded by
> [`../specs/2026-06-10-engine-b-unified-spec-v4.md`](../specs/2026-06-10-engine-b-unified-spec-v4.md).

# Engine-B — Emergent pressure in RESOLVE (leveling / relief / displacement, the refined model)

**Date:** 2026-06-07 PM · **Track:** `rebuild` · **Status:** PLAN (model ratified by user 2026-06-07 PM).
**Law:** `specs/2026-06-07-engine-b-CANONICAL-pipeline.md` + **`…-vector-map-decomposition-design.md` §8**
(the refined force model) + unified-formula §C/§J.5. **Spec wins over code.**

> Supersedes the earlier "overburden head pre-pass / column-sweep" sketch — that was a non-local extra
> step and is rejected (decomposition §8.1/§8.2). This plan implements the **one-snapshot vector resolve**
> where pressure EMERGES from gravity + incompressible reflection over ticks.

---

## The bug (root, confirmed in code)
`core/engine_b.hpp:633` drives the pressure flux from each cell's **local EOS** `p`:
```cpp
float p_face = 0.5f * (ei->cells[i].p + ej->cells[j].p);   // eos_pressure(m,T)
```
Under `max==default` a resting full liquid column has every cell at `m_rest` ⇒ `eos_pressure → 0` ⇒
`p_face = 0` ⇒ **no gradient ⇒ no leveling / relief / displacement.** This is the banked bug.

## The fix (decomposition §8 — do NOT widen the EOS, do NOT sum the column)
**One force VECTOR, one snapshot pass per tick.** Pressure emerges over ticks:
1. gravity adds `m·g·dt` downward momentum each tick (already in `u_g`);
2. an incompressible floor/cell can't accept mass (`max==default` + capacity clamp) ⇒ it **reflects** that
   blocked momentum into **pressure** (§C.5) — the live wall-reaction branch (`engine_b.hpp:639–649`) is
   the seed; it must also fire on **incompressible-fluid-receiver** faces, not only frozen-terrain walls;
3. that pressure raises the scalar that feeds `p_face`, which the same vector field carries up/sideways
   next tick;
4. after ~H ticks → hydrostatic (equal `p` at equal depth, level surfaces).

Each cell reads only its 6 neighbours' one scalar — no column/row knowledge (GPU-local). Transmission is
**yield-gated**: fluid passes `F_in + g·m` down / `F_in` sideways; a **locked solid bears+blocks** (shields
below); vacuum/free-surface resets. `max==default` stands; no band; no global Σ.

---

## Tasks (subagent-driven, TDD, real-LUT, conserve grand + per-species every step)

**T1 — RED acceptance tests (real LUT), watch them fail.** One file, all asserting *it moved/leveled* AND
conservation+bounds:
- **hydrostatic REST:** a uniform full water column does **not** move (`Δm ≈ 0` to FP), grand+species exact.
- **leveling:** two unequal same-species columns joined at the base converge toward equal height
  (monotone, no overshoot past mean ± min_mass); a single flat pool never climbs.
- **U-tube:** the worked §8.4 case levels across a connecting channel.
- **solid-shelf shielding:** a stone shelf mid-column ⇒ the fluid directly below is shielded from the
  stack above it (lower driving pressure than an open column at the same depth); pools either side of a
  vertical wall do **not** level through it.
- **swap by resistance:** lava-on-water inverts (lava sinks) when buoyant `(ρ_up−ρ_low)gV` exceeds the
  pair resistance; a high-viscosity/cohesion pair stays metastable; light-on-heavy never swaps. Permutation
  ⇒ per-species exact.
- **min_mass gate:** the 130-drop-onto-875 case → blocked; a 250 drop → `[1000,125]` (decomposition §8.7).
- **lowest-P consumption:** a displacement consumes the lowest-`p` *reachable* cell (any of 6 dirs), not a
  buried higher-`p` bubble; `max_mass` does not gate it.

**T2 — Make pressure EMERGE (the crux, HIGH RISK).** In `resolve_world`, extend the reflection so blocked
gravitational momentum becomes pressure on **incompressible-fluid-receiver** faces (not only terrain walls),
and feed that dynamic pressure into `p_face` (replacing the dead local-EOS-only value). Keep it ONE pass,
ONE snapshot, antisymmetric (conservation untouched). Make the **hydrostatic-REST** + **leveling** tests
GREEN. *Risk:* this is iterative-relaxation incompressible pressure — may need a relaxation factor
`G.head_relax` and several iterations; red-team convergence + conservation. Determinism golden will change —
regenerate with sanity asserts.

**T3 — Yield-gated transmission.** Ensure force passes through fluid (down accumulate via the per-tick
reflection, sideways transmit), **blocks at a locked solid** (terrain = `viscosity/yield = ∞`; verify the
shelf-shielding test), resets at vacuum/free-surface. No new pass — it is the per-face gate inside T2's loop.

**T4 — Swap barrier = pair resistance (drop `swap_threshold`).** Replace the global `swap_threshold`
constant with the **pair's material resistance** (viscosity/cohesion) derived from the two materials; keep
the GPU-safe gather + one-swap-per-cell + flux-XOR-swap (§2). Buoyant driver = local `(ρ_up−ρ_low)gV`
(overburden cancels). Make the swap-by-resistance test GREEN.

**T5 — min_mass gate audit.** Verify/fix `engine_b.hpp:556–575` + canDrain pre-pass to exactly match §8.7
(the 130/875/250 worked example as a test). Ensure leveling never creates sub-min fragments.

**T6 — Stability soak.** Deep column, U-tube, multi-arm, mixed A/W/L/S basin: no overshoot, energy bounded,
no oscillation. Tune `G.head_relax` if it rings (gradual leveling = accepted §6 tradeoff). Watch perf
(resolver is the ~100× hot path — note cell-rate, don't optimise yet).

**T7 — Checkpoint + push.** Full engine cheap+heavy tiers green on the real LUT; rebuild `liborge.so`;
Java `:core:test` + `:core:integrationTest` on the real `.so`; both loaders build; push engine + bump
parent gitlink + push parent. **In-game re-audit = the gate** (leveling + place-displacement should now
emerge; lava sinks; no residue).

**T8 (only after T7 proves in-game) — spec-pure placement displacement.** Switch placement to inject +
deposit the displaced incumbent as a transient over-max in the best neighbour; let RESOLVE relieve it over
ticks (handles vertical / multi-column / partial / complex uniformly). Rip out the 1D injection-chain
(`find_chain_hop` / `relocate_chain` in `sim_engine.hpp`). **Keep the chain as the stopgap until leveling
is proven in-game** — do not remove it earlier. Separate follow-on plan.

## Non-negotiables
- Grand + per-species mass EXACT every step (antisymmetric flux + permutation swaps; `p` from snapshot).
- No fabrication; no `max_mass` overshoot beyond a transient that relaxes next tick.
- `max == default` unchanged; no EOS band; no global Σ; no extra pass; no phase/state branch.
- Headless-repro-first; **adversarial review of the reflection (T2) and swap (T4) before push.**

## Risk register
- **T2 convergence** is the real unknown (emergent incompressible pressure via reflection). If it won't
  converge stably in a few iterations, fall back to a bounded relaxation and accept gradual leveling;
  escalate to the user before adding any band or implicit solve (both are off-spec).
- **Perf:** the reflection iteration compounds the ~100× resolver cost; calibration is a later stage, but
  if it lags the in-game gate, raise it.
