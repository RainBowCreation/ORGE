# ORGE Engine-B — DESIGN LAW

**FROZEN. Only the user edits this file.** This is the design. Every spec, plan, handoff, review, and
line of code is subordinate to it. It is intentionally tiny so it cannot drift and cannot be reinterpreted.

---

## The law

1. **Pressure = ONE number per cell** — a single scalar `P`. Never two terms, never split by direction.
   Depth lives *inside* `P` (a deep cell carries a big `P`, a surface cell a small `P`). `P` is the only
   persisted *intensive* field — it is iteratively relaxed, so it carries across ticks.

2. **Force = ONE Vector3 per cell** (`Fx, Fy, Fz`), accumulated into the cell's **momentum** in RESOLVE
   from three sources: (i) the **internal** 6-face term — each face contributes `P_self − P_neighbor`,
   opposing faces combine into the vector (`+x/−x → Fx`, `+y/−y → Fy`, `+z/−z → Fz`), applied as `−∇P·dt`;
   (ii) **gravity** — a global acceleration `g` (set at material-table load, overridable per `step_world`),
   applied as `mass·g·dt`; (iii) an optional **external momentum-impulse** array supplied per cell by the
   caller, added directly. A cell reads only the neighbor it touches across each face for (i) — nothing
   global, no column sums.

3. **The same rule acts in all 6 directions.** Up, down, and sideways are identical. A deep side-hole gushes
   because the deep cell's big `P` faces a small `P` across that face — automatically, with no special
   "vertical vs horizontal" handling.

4. **One step: ENCODE → RESOLVE → DECODE** (one `step_world`). ENCODE is per-cell **local** (apply gravity
   + external impulse to `momentum`; cache `T` and the gas EOS — no neighbor reads). RESOLVE is the **only**
   cross-cell step (relax `P`, build the 6-face force, move mass + heat). DECODE is per-cell **local**
   (derive `T`/`v`, relabel, write back).

5. **A cell moves when its net force beats its resistance.** Resistance is a *threshold*
   (yield_stress / cohesion). Viscosity is a *rate* only — never a threshold.

6. **Thermal rides the same pipeline.** Each cell encodes **enthalpy `E`** (`E = mass·cp·T`); temperature is
   *derived* (`T = E/(mass·cp)`), never the stored source of truth. Heat moves in **RESOLVE only**, two ways:
   **conduction** = the 6-face flux `k·(T_i − T_j)` (mass-free, antisymmetric → energy exact), and
   **advection** = enthalpy `ṁ·h` carried by the moving mass. Same shape as the force: one carried scalar per
   cell, one isotropic 6-face flux. No separate conduction pass, no per-phase branch.

7. **State — store EXTENSIVE, derive INTENSIVE.** Each cell stores only conserved extensive quantities plus
   its material and pressure: `matIx, mass, momentum (px,py,pz), enthalpy E, P`. Intensive quantities are
   **derived every tick, never stored**: `velocity = momentum/mass`, `T = E/(mass·cp)`. Extensive storage
   makes advection structurally conservative and keeps thinned cells bounded; a stored raw `v` or raw `T`
   is the velocity-ghost / temp-ghost drift — **forbidden**.

8. **Material LUT = a fixed schema** (adding or removing a field is a law change): `heatCapacity,
   thermalConductivity, molarMass, minMass, maxMass, viscosity, defaultMass (= EOS rest density m₀),
   yieldStress`, plus the phase quadruple `minTemp→minTarget`, `maxTemp→maxTarget` — **kept in the engine
   LUT** (not Java) so DECODE relabels locally, keeping `E`. `viscosity` is the rate / movability axis
   (`+INF` = frozen); `yieldStress` is the threshold axis (`0` for all current fluids — present, deferred).

9. **Mass moves, never vanishes.** Inside the domain mass only *moves* — conservative antisymmetric flux
   (donor-budget + receiver-room clamps) or a permutation swap. The only source/sink is the caller's
   place/break at the boundary, separately ledgered. A cell that is pushed but has **no escape** is a
   **no-op** (mass stays; it compresses via EOS) — never deleted. A `no_escape` detection seam fires on
   that case (empty body for now) for future handling; the default is do-nothing, never destroy.

---

## Drift test (mechanical — no debate)

Any spec, plan, or code that introduces **(a) a second pressure number**, **(b) a force rule that
differs by direction**, or **(c) a stored temperature treated as source-of-truth (instead of derived from
enthalpy) or a separate conduction pass** — is **drift. Reject it.**

> The current engine's `A + B` split (overburden + own-weight head, own-weight used only sideways) violates
> both (a) and (b). It is **DEBT**, not design. It exists only because the engine cannot yet build a correct
> single `P` (the cheap method reads 0 at rest and is dirty at edges). It is tracked in the working spec as a
> deviation **with an exit criterion** (build a real single-`P` solve → delete the second term → the six-face
> force handles everything). It is **never** copied into this file.

---

## How to use this file (every handoff / agent / review)

- **Read this file first, verbatim.** It is the truth. If anything you write contradicts it, *you are wrong*,
  not the law.
- **Do NOT edit this file** unless you are the user changing the design.
- The working spec (`specs/2026-06-09-engine-b-unified-flow-force-resistance-design.md`) describes the
  *current implementation and its deviations*. It is subordinate. Where it disagrees with this file, **this
  file wins.**
- A review's first question is **"does the artifact match this law, and is every gap filed as labeled debt?"**
  — never "does the spec match the code?" (that question is what laundered the workaround into the design).
