# ORGE Engine-B — DESIGN LAW

**FROZEN. Only the user edits this file.** This is the design. Every spec, plan, handoff, review, and
line of code is subordinate to it. It is intentionally tiny so it cannot drift and cannot be reinterpreted.
**Amended 2026-06-10: the user ratified amendments 1–9** (proposed off the 2026-06-10 physics/math audit;
the proposal record and the pre-amendment text live in git history).
**Amended 2026-06-13 (v4.2): the user ratified the whole-arc drift-audit amendments** — the universal cell
law (#0), the free-surface boundary-face ghost + density-difference face correction (#2), DECODE
no-penetration clamps (#4), `yieldStress` as a force threshold (#8), and the two-sided mass-legality law
`min ≤ m ≤ max ∨ m = 0` (#9). Proposal + recorded decisions:
`DESIGN-LAW-AMENDMENTS-PROPOSED-2026-06-11.md`; pre-amendment text in git history.
**Amended 2026-06-16 (v4.3 — ABSOLUTE NO-BRANCH): the user ordered every gas/liquid/solid classification
purged from the law AND the spec.** Root cause of a 3-week regression loop: this file said *"gas means
`χ > 0.999`"* (#8) and *"the one sanctioned material-class term … ε=0 for gases"* (#0/#6); the spec copied
it as "Gas classification: χ > 0.999"; the engine implemented `is_gas`/`GAS_CHI_MIN`; the audit (code-vs-spec)
blessed it. #0 is rewritten below as the absolute no-branch rule — **no classification step exists anywhere**;
every float is a continuous weight/limit and every regime falls out by branchless saturating math. Forbidden
symbols: `is_gas, is_liquid, is_solid, is_compressible, GAS_CHI_MIN, PR_GAS/PR_LIQUID/PR_SOLID`, any
`switch(state)`, any `if` on a material category or a threshold of a material-derived value.

---

## The law

0. **One universal cell law — ZERO branches, ever.** Every cell obeys ONE formula. "solid", "liquid",
   "gas", "vacuum" are **not** systems, states, categories, or classes — they are only *names humans use*
   for regions of a continuous material-parameter space: vacuum ≈ `mass 0` (`min = max = 0`); a gas-like
   cell ≈ low `min/default/max` with a wide compression band (`χ` near 1), `τ_y = 0`; a liquid-like cell ≈
   no compression band (`max = default` ⇒ `χ = 0`), `τ_y = 0`; sand ≈ **finite** `τ_y`; terrain ≈
   `viscosity → ∞` / `τ_y → ∞`. These names NEVER appear in the engine as a test. All behavior — pooling,
   stratification, holding, slumping, compression — is **emergent** from the one formula + gravity + the EOS
   + `min ≤ m ≤ max` (#9).

   **The absolute rule (no exceptions, no "sanctioned" cases):** the engine MUST NOT contain ANY branch that
   selects behavior by what a cell *is*. Forbidden — not only the obvious `switch(state)` / `if (isGas)` /
   `if (isLiquid)`, but EVERY disguised form: `if (χ > c)`, `if (maxMass == minMass)`, `if (viscosity == ∞)`,
   `if (τ_y > 0)`, or **computing any value and then using it to decide "this cell is a solid/liquid/gas" and
   forking on it.** There is no classification step anywhere — not in the kernel, not in a helper, not as a
   cached flag. Each material float (`χ, μ, τ_y, min, default, max, M, ε, β`) enters the single formula as a
   **continuous weight or limit**, so every degenerate case (solid, vacuum, incompressible, frozen) **falls
   out as a limit** of that one formula, reached ONLY by branchless saturating math — `min`, `max`, `clamp`,
   `abs`, multiplicative weights (`χ·x`, `(1−χ)·x`), and regularized division (e.g. `a/(b+ε)` so `b = 0`
   needs no `if`). Permitted vocabulary: branchless saturating arithmetic. Forbidden: any `if` / `?:` /
   `switch` whose condition is a material category, a material *name*, or a threshold on a material-derived
   quantity. If a rule seems to *need* such a branch, the **formula** is wrong — rewrite it so the limit
   emerges; never add the branch. Even radiation's `ε = 0` is just data: the flux `ε_eff·σ·(T⁴−T⁴)` is **0**
   at `ε = 0` by arithmetic — there is no gas test (#6).

1. **Pressure = ONE number per cell** — a single scalar `P`. Never two terms, never split by direction.
   Depth lives *inside* `P` (a deep cell carries a big `P`, a surface cell a small `P`). `P` is the only
   persisted *intensive* field — it is iteratively relaxed, so it carries across ticks.

2. **Force = ONE Vector3 per cell** (`Fx, Fy, Fz`), accumulated into the cell's **momentum** in RESOLVE
   from two sources: (i) the **internal** 6-face term — an **interior** fluid|fluid face carries
   `½(P_self + P_neighbor) + ¼(ρ_neighbor − ρ_self)·g⃗·(r⃗_face − r⃗_self)` (the density-difference
   correction makes the two sides' half-cell hydrostatic extrapolations agree at the shared face); a
   **boundary** face (against gas, vacuum, or a solid wall) carries the cell's own half-cell hydrostatic
   ghost `P_self + ρ_self·g⃗·(r⃗_face − r⃗_self)` — the boundary condition itself, *not* an average with the
   far side (an average leaves `−m·g/2` on every free surface, a permanent vel_damp heat pump). The six
   faces combine as the closed surface integral
   `F⃗ = −Σ_faces p_face·A·n̂_out` (= `−∇P·V`; net per axis `½(P_low-side − P_high-side)·A`), applied as
   the impulse `−∇P·V·dt`; (ii) **advected momentum** — moving mass carries its momentum `ṁ·u_donor`.
   **Gravity** (a global acceleration `g`, set at material-table load, overridable per `step_world`) and
   the optional caller-supplied **external momentum-impulse** are applied **once, in ENCODE** (they need
   no neighbor). A cell reads only the neighbor it touches across each face for (i) — nothing global,
   no column sums.

3. **The same rule acts in all 6 directions.** Up, down, and sideways are identical. A deep side-hole gushes
   because the deep cell's big `P` faces a small `P` across that face — automatically, with no special
   "vertical vs horizontal" handling.

4. **One step: ENCODE → RESOLVE → DECODE** (one `step_world`). ENCODE is per-cell **local** (apply gravity
   + external impulse to `momentum` — the one and only application; cache `T` and the gas EOS — no neighbor
   reads). RESOLVE is the **only** cross-cell step (relax `P`, build the 6-face force + advected momentum,
   move mass + heat). DECODE is per-cell **local** (derive `T`/`v`, relabel, write back) — except a
   **no-penetration clamp** may read the 1-hop snapshot neighbor to zero a velocity component pointing into
   a wall or cross-species no-flux face: a boundary condition that zeroes velocity and its derived momentum
   (mass and `E` untouched), order-independent — never transport.

5. **A cell moves when its net force beats its resistance.** Resistance is a *threshold*
   (yield_stress / cohesion). Viscosity is a *rate* only — never a threshold.

6. **Thermal rides the same pipeline.** Each cell encodes **enthalpy `E`** on its material's **enthalpy
   curve** `E = m·h(T)` — piecewise linear in `T` (slope `cp`) whose inverse has **plateaus of width
   `latentHeat`** at the phase thresholds; phase-paired curves are **chain-anchored**
   (`h_target(T*) ≡ h_donor(T*) + L` at each plateau's far edge), so a relabel is the identity on `E`
   (**ΔE ≡ 0**) and `T` is continuous across every transition. Temperature is *derived*
   (`T = h⁻¹(E/m)`), never the stored source of truth. Heat moves in **RESOLVE only**, three ways:
   **conduction** = the 6-face flux `k_face·(T_i − T_j)·(A/Δx)·dt` (`k_face` = harmonic mean — mass-free,
   antisymmetric → energy exact); **radiation** = the 6-face flux `ε_eff·σ·(T_i⁴ − T_j⁴)·A·dt`
   (`ε_eff` = the condensed side's `ε` against a transparent partner, `ε_i·ε_j` between condensed cells —
   which exchange only when `|ΔT| > 300 K`, the film-boiling surrogate; gas partners absorb locally;
   vacuum faces exchange with a world `T_sky`, boundary-ledgered); and **advection** = the moving mass
   carries its **momentum `ṁ·u_donor`** and its **enthalpy `ṁ·h_donor`**. All explicit thermal fluxes are
   bounded by the **discrete maximum principle, enforced conservatively** (the offending FACE fluxes are
   scaled symmetrically — never a one-sided clip, which would create/destroy energy). Same shape as the
   force: carried extensive quantities per cell, isotropic antisymmetric 6-face fluxes. No separate
   conduction pass. `ε_eff` is a **continuous function of the two faces' `ε` data** (and the deposition
   split is `ε`-weighted), so at `ε = 0` the radiative flux is **0 by arithmetic** — "gases don't radiate"
   emerges from the number, NOT from a gas test. (The `|ΔT| > 300 K` film-boiling gate is itself a threshold
   branch and must become a branchless smooth ramp — tracked under #0.)

7. **State — store EXTENSIVE, derive INTENSIVE.** Each cell stores only conserved extensive quantities plus
   its material and pressure: `matIx, mass, momentum (px,py,pz), enthalpy E, P` — plus two persisted
   **bookkeeping** fields with no physical meaning (`swapReady`, the swap-cadence accumulator; `void_ix`,
   the engine free-list index). Intensive quantities are **derived every tick, never stored**:
   `velocity = momentum/mass`, `T = h⁻¹(E/m)`. Extensive storage makes advection structurally conservative
   and keeps thinned cells bounded; a stored raw `v` or raw `T` is the velocity-ghost / temp-ghost drift —
   **forbidden**.

8. **Material LUT = a fixed schema** (adding or removing a field is a law change): `heatCapacity,
   thermalConductivity, molarMass, minMass, maxMass, viscosity, defaultMass (= EOS rest density m₀),
   yieldStress, emissivity, thermalExpansion, latentHeatMin, latentHeatMax`, plus the phase quadruple
   `minTemp→minTarget`, `maxTemp→maxTarget`, plus a per-gas `T_ref` — **kept in the engine LUT** (not
   Java) so DECODE relabels locally. Compressibility weight: `χ = (maxMass − defaultMass)/(maxMass −
   minMass + ε_χ)` — a **continuous weight in [0,1]**, computed with a regularized denominator so
   `maxMass == minMass` yields `χ = 0` **with no `if`-guard** (the numerator `maxMass − defaultMass` is also
   0 in every `max == default` case, so χ→0 falls out). **`χ` is NEVER compared to a threshold and NEVER
   used to classify** — there is no "gas means χ > c". It enters formulas ONLY as a continuous multiplier
   (`χ·…`, `(1−χ)·…`). `molarMass` is the EOS input (#9). `viscosity` is the rate / movability axis (`+INF` = frozen);
   `yieldStress` is the threshold axis — a **force threshold [N]** (`0` for fluids, finite for granular,
   `+INF` for solids; the move gate compares net face force against `max(τ_y,i, τ_y,j)`, N vs N).

9. **Mass moves, never vanishes.** Inside the domain mass only *moves* — conservative antisymmetric flux
   (donor-budget + receiver-room clamps) or a permutation swap. The only source/sink is the caller's
   place/break at the boundary, separately ledgered. A pushed cell with **no escape** is a **no-op**
   (mass stays). **Every** cell carries the same EOS pushback weighted continuously by `χ`:
   `χ · p_eos`, `p_eos = (m/M)·R·T/V − P₀` (gauge; `P₀` = rest-state pressure at `T_ref`). At `χ ≈ 1` a 700×
   compressed pocket resists with real megapascals; at `max == default` ⇒ `χ = 0` the compression term is
   **0 by the multiplier** (no "incompressible" branch) and the cell's relief is its pressure `P` rising
   through the relaxation (`P` is the constraint force). Same formula, both ends of `χ`; mass is never
   deleted. A `no_escape` detection seam fires on that case (empty body for now) for future handling; the
   default is do-nothing, never destroy.

   **Every cell is mass-legal: `min ≤ m ≤ max` or `m = 0`** — never a sub-min or over-max cell, *from any
   path*, not even for one tick. Enforced at every flow, relabel, and eviction site: flow's cohesion/room
   gates already leave a donor `≥ min` or fully drained and a receiver `≤ max`; a **relabel** (phase change
   or empty-cell adoption) is **forbidden when the carried mass `< min(target)`** — the cell keeps its
   species/mass/`E` until it legally clears the target min (keep-`E`, so blocking conserves energy exactly);
   a **relabel into a lower-`max` species** (e.g. water→ice) must **evict its excess in the same pass** to a
   legal neighbor (mass + `E` carried, obeying both bounds) or **defer** if no legal target exists — never
   sit over-max.

---

## Drift test (mechanical — no debate)

Any spec, plan, or code that introduces **(a) a second pressure number**, **(b) a force rule that
differs by direction**, **(c) a stored temperature treated as source-of-truth (instead of derived from
the enthalpy curve) or a separate conduction pass**, or **(d) ANY branch keyed on state/category —
`switch(state)`, `if (isGas/isLiquid/isSolid)`, `if (χ > c)`, `if (max == min)`, `if (μ == ∞)`, a
`PR_GAS`-style class, or a computed flag that classifies a cell and forks on it** — is **drift. Reject it.**
Test (d) mechanically: the engine source must contain none of `is_gas`, `is_liquid`, `is_solid`,
`is_compressible`, `GAS_CHI_MIN`, `PR_GAS`; and no comparison of `χ`/`viscosity`/`τ_y` against a constant
that selects a code path. Behavior comes from continuous weights (`χ·…`) and saturating math only.

> The engine's **code** still carries the `A + B` split (overburden + own-weight head, own-weight used only
> sideways) — it violates both (a) and (b) and remains **DEBT**, not design. Its exit is now fully
> specified and ratified: the working spec's single-`P` relaxation (v4 §3) — implement it, delete the
> second term, and the six-face force handles everything. Until that lands, the split stays tracked debt;
> it is **never** copied into this file.

---

## How to use this file (every handoff / agent / review)

- **Read this file first, verbatim.** It is the truth. If anything you write contradicts it, *you are wrong*,
  not the law.
- **Do NOT edit this file** unless you are the user changing the design.
- The working spec is **the dated spec named in `handoffs/00-MASTER-RULES.md`** (currently
  `specs/2026-06-10-engine-b-unified-spec-v4.md`). It describes the current implementation and its
  deviations; it is subordinate. Where it disagrees with this file, **this file wins.**
- A review's first question is **"does the artifact match this law, and is every gap filed as labeled debt?"**
  — never "does the spec match the code?" (that question is what laundered the workaround into the design).
